// Prints the current one-time code for a TOTP secret, the way an authenticator app does (RFC 6238:
// HMAC-SHA1, 6 digits, 30-second step). For trying the second factor locally without a phone.
//
//   node tests/mfa/totp.mjs "ZTVC 2MIV MYWS 7T74 R6NK JDGX XJXE FFX3"
//   node tests/mfa/totp.mjs "<secret>" 1      # the code for the next 30 seconds
//
// The secret is the key shown on the enrolment screen; spaces and case do not matter. A code is
// accepted once, so after enrolling with one, sign in with the next step's code (offset 1) or wait.
import { createHmac } from 'node:crypto';

const [secretText, offset = '0'] = process.argv.slice(2);
if (!secretText) {
  console.error('usage: node tests/mfa/totp.mjs "<secret>" [step-offset]');
  process.exit(2);
}

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
let bits = 0;
let buffer = 0;
const bytes = [];
for (const c of secretText.replace(/\s/g, '').toUpperCase()) {
  const value = ALPHABET.indexOf(c);
  if (value < 0) {
    console.error(`not a Base32 character: ${c}`);
    process.exit(2);
  }
  buffer = (buffer << 5) | value;
  bits += 5;
  if (bits >= 8) {
    bytes.push((buffer >> (bits - 8)) & 255);
    bits -= 8;
  }
}

const step = Math.floor(Date.now() / 1000 / 30) + Number(offset);
const message = Buffer.alloc(8);
message.writeBigUInt64BE(BigInt(step));
const hash = createHmac('sha1', Buffer.from(bytes)).update(message).digest();
const at = hash[hash.length - 1] & 15;
const binary =
  ((hash[at] & 0x7f) << 24) | (hash[at + 1] << 16) | (hash[at + 2] << 8) | hash[at + 3];
const secondsLeft = 30 - (Math.floor(Date.now() / 1000) % 30);
console.log(String(binary % 1_000_000).padStart(6, '0'), `(changes in ${secondsLeft}s)`);
