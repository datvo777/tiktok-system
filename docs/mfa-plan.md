# Plan: xác thực hai yếu tố (MFA) cho ứng dụng

Ghi chú bàn giao cho Claude Code. Đường dẫn tính từ gốc repo `tiktok-system`. Soạn 2026-10-11 sau khi đọc code ở
`AuthController`, `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`, `LoginRateLimiter`, `AccountService`,
`AdminActionRecorder` và `admin-web/src/api.ts`. Chưa có dòng code MFA nào được viết.

## Trạng thái cài đặt (2026-10-11): giai đoạn 1 đã làm xong, chưa commit

Đã chốt theo yêu cầu: **áp dụng cho ADMIN trước, bật tắt bằng biến môi trường** (`MFA_ENABLED`, mặc định `false`).
Các câu hỏi mở ở mục 6 đã lấy mặc định: khoá mã hoá qua biến môi trường `MFA_ENCRYPTION_KEY`; reset MFA của ADMIN
ngoài API (mục "Việc còn lại"); người dùng thường để giai đoạn 2; đổi cấu hình MFA ghi vào cột mới `mfa_changed_at`
(không bump `password_changed_at`).

Đã có: migration `V41__account_mfa.sql`; claim `amr` trong session (giữ qua `/auth/refresh`, không bao giờ nâng bằng
refresh); token trung gian `mfa-pending` với khoá dẫn xuất riêng; TOTP RFC 6238 tự cài, kiểm bằng test vector; bí mật
mã hoá AES-GCM, không khởi động nếu thiếu khoá; mã khôi phục băm; chống phát lại bằng `last_used_step`; bộ đếm thử
mã **fail-closed** (503 `MFA_UNAVAILABLE`); ép ở `/internal/**` (403 `MFA_REQUIRED`); audit `account.mfa_enrolled` và
`account.mfa_recovery_used`; admin-web có màn đăng ký, bước nhập mã, mã khôi phục. Cờ tắt thì các route `/auth/mfa/*`
trả 404 và mọi thứ như cũ (`MfaDisabledIT`).

Đã kiểm: 40 test đơn vị mới và sửa (TOTP, Base32, mã hoá, mã khôi phục, token trung gian, `amr`, bộ giới hạn, refresh);
`MfaFlowIT` 20 test và `MfaDisabledIT` 2 test trên Postgres và Redis thật; `AuthorizationIT`, `AccountFlowIT`,
`ModerationPublicationFlowIT`, `SessionLifecycleIT` vẫn pass; thử trên trình duyệt thật (đăng ký, mã sai, mã đúng, tải
lại trang, đăng xuất, mã khôi phục viết thường có khoảng trắng).

**Việc còn lại và khác với plan:**

- **Mã QR đã có** (thêm sau giai đoạn 1): màn đăng ký vẽ QR bằng `qrcode-generator` 2.0.4 (MIT, không phụ thuộc phụ),
  dựng trong trình duyệt từ chính chuỗi `otpauth://`, nên khoá không đi tới dịch vụ nào. Đã kiểm: giải mã ảnh chụp màn
  hình bằng bộ nhận dạng QR của macOS cho đúng từng ký tự chuỗi `otpauth://` trên trang. Khoá nhập tay và liên kết
  `otpauth://` vẫn còn làm phương án dự phòng. Chưa thử quét bằng ứng dụng xác thực thật.
- **Không có thông báo trong ứng dụng khi dùng mã khôi phục.** Module `account` không phụ thuộc `notification`; hiện chỉ
  có dòng audit. Nếu cần, thêm qua một sự kiện outbox.
- **Reset MFA của ADMIN chưa có công cụ.** Quy trình khẩn cấp (cần hai người và log) hiện là SQL:
  `DELETE FROM account.mfa_recovery_code WHERE account_id = …; DELETE FROM account.mfa_factor WHERE account_id = …;
  UPDATE account.account SET mfa_changed_at = now() WHERE account_id = …;` (dòng cuối thu hồi các phiên cũ). Chưa
  viết thành lệnh quản trị có log.
- **Chưa làm:** tắt tự phục vụ cho người dùng thường, step-up cho thao tác nguy hiểm, WebAuthn (giai đoạn 2 và 3),
  xoay khoá `MFA_ENCRYPTION_KEY` (cột `key_id` đã có chỗ, chưa có mã xoay).
- **Chưa đo tác động hiệu năng của đăng nhập** (thêm một truy vấn `count(*)` mỗi lần đăng nhập khi cờ bật).
- **Thử thủ công chưa làm:** ứng dụng xác thực thật trên điện thoại (mình dùng bộ tính TOTP độc lập), lệch giờ máy chủ.

## 0. Mục tiêu, phạm vi, trạng thái hiện tại

**Mục tiêu:** một mật khẩu bị lộ (lừa đảo, dùng lại mật khẩu, credential stuffing) không đủ để dùng quyền ADMIN.

**Phạm vi theo giai đoạn** (làm lần lượt, mỗi giai đoạn tự đứng được):

| Giai đoạn | Nội dung |
|---|---|
| 1 | TOTP bắt buộc cho role ADMIN: đăng ký, đăng nhập hai bước, mã khôi phục, ép ở `/internal/**` |
| 2 | Xác nhận lại (step-up) cho thao tác nguy hiểm; MFA tuỳ chọn cho người dùng thường |
| 3 | Passkey / WebAuthn như yếu tố thứ hai (chống lừa đảo tốt hơn TOTP) |

**Ai bị áp dụng.** Phần *bắt buộc* chỉ dành cho role trong `shortvideo.mfa.required-roles` (mặc định `ADMIN`) và chỉ
được ép ở `/internal/**`. Phần *cơ chế* (đăng nhập hai bước, yếu tố, endpoint đăng ký và xác minh) dùng chung cho mọi
tài khoản: một tài khoản bất kỳ có yếu tố `ACTIVE` thì luôn bị hỏi mã khi đăng nhập, dù API của người dùng không đòi
`amr`. Giai đoạn 1 chỉ có màn đăng ký ở admin-web; trang cài đặt cho người dùng thường trong `web` là giai đoạn 2, nên
ở giai đoạn 1 thực tế chỉ admin đăng ký. Mở cho người dùng sớm hơn chỉ cần thêm trang đó, không đổi backend.

Ngoài phạm vi: SMS/email OTP (yếu nhất, hoán đổi SIM, chiếm email), đăng nhập theo rủi ro, "nhớ thiết bị 30 ngày"
(bỏ qua có chủ ý ở lần đầu: nó mở lại đúng cái cửa mà MFA đóng).

**Sự thật đã xác minh trong code (đừng đoán lại):**

- Không có MFA nào trong repo (tìm `totp|mfa|2fa|webauthn|passkey|otp` theo ranh giới từ, không có kết quả).
- `POST /api/v1/auth/login` ([AuthController.java](backend/modules/account/src/main/java/com/shortvideo/account/web/AuthController.java))
  kiểm tra mật khẩu rồi **cấp cookie phiên ngay**; phản hồi `LoginResponse` gồm `accountId, token, expiresAt`.
- Session là JWT HS256 với các claim `jti, sub, iss, aud, iat, exp, authTime, roles, type`
  ([JwtService.java](backend/shared/security/src/main/java/com/shortvideo/shared/security/JwtService.java)).
  `type=session` được kiểm tra khi parse, và có khoá dẫn xuất theo loại token (`TokenKeys`), nên một token loại
  khác không dùng làm session được.
- `AuthenticatedAccount` là record `(accountId, roles, tokenId, issuedAt, expiresAt, authTime)`;
  `JwtAuthenticationFilter.stillEntitled` **dựng lại** record này mỗi request (roles đọc lại từ DB), nên claim mới
  phải được chuyển qua đó, nếu không nó biến mất sau request đầu.
- `/auth/refresh` thu hồi token cũ (`revokeIfActive`), phát token mới và **mang `authTime` qua**. Claim MFA phải làm
  giống vậy.
- `/internal/**` được ép bằng `hasRole("ADMIN")` trong `SecurityConfig` và `@PreAuthorize("hasRole('ADMIN')")`
  trên từng controller (21 chỗ dùng `ROLE_ADMIN`/`hasRole('ADMIN')`).
- Role lưu dạng chuỗi CSV `roles VARCHAR(200)` trong `account.account` (`V2__account.sql`). **Không có API cấp quyền
  ADMIN**; cấp bằng SQL (xem `tests/k6/README.md`). Chưa có người nào có thể "tự đăng ký" thành admin.
- Đổi mật khẩu làm mọi token cũ vô hiệu qua `password_changed_at` (`AccountService.changePassword`,
  `CredentialFreshnessReader/Cache`). Đây là cơ chế tái dùng cho "đổi cấu hình MFA thì phiên cũ mất hiệu lực".
- `LoginRateLimiter`: đếm theo IP (mọi lần thử) và theo tài khoản (chỉ lần thất bại), **fail-open** khi Redis hỏng
  (có chủ ý, ghi trong comment). Với MFA ta cần quyết định khác (mục 3.6).
- `AdminActionRecorder.record(AdminAction)` ghi log kiểm toán cho hành động admin; đọc qua `/internal/v1/audit`.
  Chưa rõ nó có phù hợp để ghi sự kiện bảo mật tự phục vụ (người dùng tự đăng ký MFA) hay cần mở rộng: **cần đọc
  `AdminAction` và `AuditActions` trước khi quyết** (mục 6).
- Migration đánh số **toàn cục** giữa các module; số lớn nhất hiện có là `V40`, nên migration mới là `V41`.
- admin-web gọi `login(email, password)` rồi `getMe()`; `loginSchema` chỉ nhận `{accountId, expiresAt}`
  ([admin-web/src/api.ts](admin-web/src/api.ts)). `shared/src/http.ts` có `ApiError(status, message, retryAfterMs, code)`,
  nên mã lỗi ổn định (`code`) đã có chỗ dùng.

## 1. Quyết định thiết kế (theo best practice) và lý do

1. **Đăng nhập hai bước với token trung gian, không cấp session giữa chừng.**
   Mật khẩu đúng và tài khoản cần MFA thì `/auth/login` trả `{mfaRequired: true, mfaToken}` và **không đặt cookie**.
   `mfaToken` là JWT loại `mfa-pending` (khoá dẫn xuất riêng, sống 5 phút), mang `sub` và `jti`; `jti` dùng một lần và
   cháy sau N lần nhập sai (3.6). Cách "cấp session bị giới hạn" cũng khả thi nhưng một session thật rò rỉ ra ngoài cổng
   MFA là rủi ro lớn hơn một token vô dụng ngoài `/auth/mfa/verify`.
2. **Ghi nhận phương thức xác thực trong session** bằng claim `amr` (RFC 8176: `["pwd","otp"]`), thêm vào
   `AuthenticatedAccount` như `authTime`: do `JwtService.issue` ghi, `parse` đọc, `/refresh` mang qua, `stillEntitled`
   chuyển tiếp. Token cũ không có `amr` coi như `["pwd"]`.
3. **Ép ở một chỗ** (`SecurityConfig`, cho `/internal/**`): ADMIN **và** `amr` chứa `otp` (hoặc `hwk` ở giai đoạn 3),
   không rải kiểm tra vào từng controller. Thiếu MFA trả `403` với mã `MFA_REQUIRED` (dùng `writeProblem` đã có) để
   client biết phải đăng nhập lại hoặc đăng ký, khác với 401 (phiên hỏng) và khác với 403 thiếu quyền.
4. **Bí mật TOTP mã hoá khi lưu** (AES-GCM, khoá từ biến môi trường `MFA_ENCRYPTION_KEY`, có `key_id` để xoay khoá).
   Ứng dụng **không khởi động** nếu khoá thiếu hoặc là giá trị mẫu, giống `JwtService.requireUsableSecret` và guard
   token scrape vừa thêm. Mã khôi phục lưu dạng băm.
5. **Tham số TOTP chuẩn để tương thích:** SHA-1, 6 chữ số, chu kỳ 30 giây, bí mật 20 byte (Base32), chấp nhận ±1 bước.
   Ứng dụng xác thực phổ biến bỏ qua các tham số khác, nên đổi chúng chỉ gây lỗi.
6. **Chống phát lại:** lưu `last_used_step` của yếu tố; từ chối mã có bước ≤ bước đã dùng. Không có điều này một mã
   bị nhìn trộm dùng lại được trong 30 đến 90 giây.
7. **Fail-closed cho bộ đếm MFA** (khác `LoginRateLimiter`): nếu Redis không đếm được số lần thử mã, từ chối xác minh
   bằng 503 + `Retry-After` (tái dùng lỗi 503 của `AUTH_STATE_UNAVAILABLE`). Lý do: không đếm được nghĩa là có thể thử
   mã 6 chữ số vô hạn. Fail-open ở đăng nhập là chấp nhận được vì còn bcrypt và mật khẩu; ở bước mã thì không.
8. **Không tiết lộ tài khoản có MFA hay không trước khi mật khẩu đúng.** Hành vi lỗi của `/auth/login` giữ nguyên;
   chỉ sau khi mật khẩu đúng mới trả `mfaRequired`.
9. **Đổi cấu hình MFA phải làm phiên cũ mất hiệu lực** (đăng ký, tắt, tạo lại mã khôi phục, reset): tái dùng cơ chế
   `password_changed_at`. Quyết định cần chốt: bump chính cột này (đơn giản, nhưng tên sai nghĩa) hay thêm cột
   `credentials_changed_at` và đổi tên dần (sạch, tốn thêm một migration). Mình nghiêng về cột mới.
10. **QR code dựng ở phía client** từ chuỗi `otpauth://`. Không gửi bí mật tới dịch vụ QR bên thứ ba.

## 2. Mô hình dữ liệu (`V41__account_mfa.sql`, schema `account`)

```
account.mfa_factor
  factor_id        UUID PK
  account_id       UUID NOT NULL REFERENCES account.account(account_id)
  type             VARCHAR(20) NOT NULL            -- 'TOTP' (giai đoạn 3: 'WEBAUTHN')
  state            VARCHAR(20) NOT NULL            -- 'PENDING' | 'ACTIVE'  (chuỗi, không phải ordinal: brief mục 7)
  secret_enc       BYTEA NOT NULL                  -- AES-GCM(nonce || ciphertext)
  key_id           VARCHAR(20) NOT NULL            -- khoá nào đã mã hoá, để xoay khoá
  last_used_step   BIGINT NOT NULL DEFAULT 0       -- chống phát lại
  created_at, confirmed_at TIMESTAMPTZ
  UNIQUE (account_id, type) WHERE state = 'ACTIVE' -- mỗi loại một yếu tố đang hoạt động

account.mfa_recovery_code
  account_id UUID, code_hash BYTEA, used_at TIMESTAMPTZ NULL
  -- 10 mã mỗi lần tạo; tạo lại thì xoá mã cũ
```

Mã khôi phục có entropy cao (ví dụ 10 ký tự từ 32 ký tự), nên SHA-256 hoặc HMAC là đủ; không cần bcrypt.
Giữ `account.account.roles` như cũ. Chính sách "ai phải có MFA" nằm trong cấu hình, không trong DB:
`shortvideo.mfa.required-roles=ADMIN`.

## 3. Luồng

### 3.1 Đăng ký TOTP (phiên đang đăng nhập)

1. `POST /api/v1/auth/mfa/totp/enroll` với **mật khẩu hiện tại** trong body (xác nhận lại, vì cookie phiên đơn thuần
   không chứng minh người đang ngồi đó). Tạo yếu tố `PENDING`, trả `otpauth://totp/Short:<email>?secret=...&issuer=Short`.
   Gọi lại khi đã có yếu tố `PENDING` thì thay nó.
2. `POST /api/v1/auth/mfa/totp/confirm {code}`: xác minh mã, chuyển `ACTIVE`, tạo 10 mã khôi phục và **trả một lần**,
   bump `credentials_changed_at`, cấp **phiên mới** có `amr=["pwd","otp"]` (thu hồi phiên hiện tại như `/refresh`).
3. Một ADMIN chưa đăng ký MFA vẫn đăng nhập được (password-only, `amr=["pwd"]`) nhưng `/internal/**` trả `MFA_REQUIRED`
   và các route `/auth/mfa/*` mở, nên họ tự đăng ký được; chưa có gì kẹt vòng "cần MFA để đăng ký MFA".

### 3.2 Đăng nhập

1. `POST /auth/login`: như cũ cho tới khi mật khẩu đúng. Nếu tài khoản có yếu tố `ACTIVE`, **hoặc** vai trò nằm trong
   `required-roles` và đã đăng ký: trả `{mfaRequired:true, mfaToken}`, không cookie.
   Role bắt buộc mà **chưa đăng ký**: đăng nhập password-only như 3.1.3.
2. `POST /auth/mfa/verify {mfaToken, code}` hoặc `{mfaToken, recoveryCode}`: kiểm tra token (loại, hạn, `jti` chưa dùng,
   chưa cháy), kiểm tra mã (TOTP: ±1 bước, `last_used_step`, so sánh thời gian hằng số; mã khôi phục: so băm, đánh dấu
   dùng), thành công thì cấp session với `amr=["pwd","otp"]` và `authTime=now`, **đốt `jti`**.

### 3.3 Làm mới phiên

`/auth/refresh` mang `amr` và `authTime` qua. Không bao giờ nâng `amr` lên: chỉ `confirm` và `verify` được.

### 3.4 Tắt hoặc đặt lại

- Tự tắt: cần mật khẩu **và** mã hiện tại. **ADMIN không tự tắt được** (bị `required-roles`).
- Admin khác reset MFA của người dùng thường: `POST /internal/v1/accounts/{id}/mfa/reset`, ghi audit, thu hồi phiên.
- **Reset MFA của một ADMIN không qua API**: quy trình khẩn cấp do người vận hành làm (SQL hoặc lệnh quản trị có log).
  Mở nó qua API nghĩa là kẻ chiếm được một tài khoản admin có thể gỡ MFA của admin khác. Chốt: quy trình hai người.

### 3.5 Mã khôi phục

Hiện một lần khi tạo. Tạo lại thì mã cũ mất hiệu lực. Dùng một mã khôi phục thì gửi thông báo trong ứng dụng
(`NotificationService`), vì đó là dấu hiệu quan trọng (người dùng mất thiết bị, hoặc kẻ tấn công đang dùng).
Chưa có hạ tầng email trong repo (không có module email), nên thông báo là in-app.

### 3.6 Giới hạn tốc độ và chống đoán mã

- Mỗi `mfaToken`: tối đa 5 lần nhập sai, sau đó **cháy**; người dùng phải nhập lại mật khẩu.
- Mỗi tài khoản: bộ đếm thất bại riêng cho bước mã (cửa sổ 15 phút, ngưỡng ~10), khoá tạm khi vượt. Chấp nhận được hơn
  khoá ở bước mật khẩu, vì kẻ tấn công phải biết mật khẩu mới chạm tới bước này (khác nhận xét về vector khoá người
  dùng trong comment của `LoginRateLimiter`).
- Mỗi IP: đếm mọi lần gọi `/auth/mfa/verify`.
- Fail-closed khi Redis không đếm được (mục 1.7).
- Tính nhanh: xác suất đoán trúng một mã 6 chữ số với 3 mã hợp lệ trong cửa sổ ±1 là khoảng 3/1.000.000 mỗi lần thử,
  nên giới hạn 5 lần mỗi token cho khoảng 1,5/100.000 mỗi lần đăng nhập mật khẩu đúng. Cần thêm giới hạn theo tài khoản
  để tổng số lần thử không nhân lên bằng cách lặp lại bước mật khẩu.

## 4. Thay đổi theo thành phần

**Backend**

- `shared/security`: `JwtService.issue(..., amr)`, claim `amr`, loại token `mfa-pending` (khoá dẫn xuất mới trong
  `TokenKeys`), `AuthenticatedAccount.amr` (giữ constructor cũ cho tương thích), `JwtAuthenticationFilter.stillEntitled`
  chuyển tiếp `amr`, `MfaAttemptLimiter` (theo token/tài khoản/IP, fail-closed).
- `modules/account`: `MfaFactorEntity`, `MfaService` (enroll, confirm, verify, recovery, reset), `TotpCodec`
  (RFC 6238; dùng thư viện nhỏ đã kiểm chứng **hoặc** tự cài ~40 dòng và kiểm bằng test vector của RFC. Chọn khi bắt đầu,
  kiểm phiên bản thư viện trước khi thêm), `MfaSecretCipher` (AES-GCM, `key_id`), `AuthController` thêm
  `/mfa/verify`, `/mfa/totp/enroll`, `/mfa/totp/confirm`; `InternalAccountController` thêm reset.
- `app/config/SecurityConfig`: `/internal/**` yêu cầu ADMIN và `amr` có `otp`; entry point trả `MFA_REQUIRED`.
  Guard khởi động cho `MFA_ENCRYPTION_KEY`.
- Cấu hình: `shortvideo.mfa.enabled` (mặc định **false**), `required-roles`, `issuer`, `pending-ttl`, ngưỡng giới hạn.
- `V41__account_mfa.sql` như mục 2; cột `credentials_changed_at` nếu chọn phương án sạch.

**Frontend**

- `admin-web`: `login()` trả union `{signedIn}` | `{mfaRequired, mfaToken}` (đổi `loginSchema`); màn nhập mã 6 chữ số
  (và liên kết "dùng mã khôi phục"); xử lý `ApiError.code === 'MFA_REQUIRED'` bằng cách chuyển sang màn đăng ký; màn
  đăng ký (QR dựng phía client, ô nhập mã xác nhận, hiển thị mã khôi phục một lần kèm xác nhận "đã lưu").
- `web` (giai đoạn 2): trang cài đặt bảo mật cho người dùng.
- `shared/src/http.ts`: không cần đổi; `ApiError.code` đã mang mã ổn định.

## 5. Kiểm thử

- **Đơn vị:** test vector RFC 6238; cửa sổ ±1; từ chối mã đã dùng (`last_used_step`); mã khôi phục dùng một lần; so
  sánh hằng số; mã hoá khoá sai không giải mã được.
- **Tích hợp** (`AuthorizationIT`, mô hình sẵn có): session chỉ-mật-khẩu không vào được `/internal/**` (403
  `MFA_REQUIRED`); session có MFA vào được; `/refresh` giữ `amr`; `mfa-pending` không dùng làm session (và ngược
  lại); token cháy sau 5 lần sai; token không dùng lại được; đổi cấu hình MFA thu hồi phiên cũ; Redis hỏng thì bước mã
  trả 503 chứ không cho thử không giới hạn; cờ tắt thì hành vi cũ giữ nguyên (không thay đổi cho người dùng hiện tại).
- **Web:** đăng nhập hai bước; xử lý `MFA_REQUIRED`; không lưu `mfaToken` vào localStorage.
- **Kiểm tra thủ công trên trình duyệt thật** với ứng dụng xác thực thật: mã ±1 bước, lệch giờ, đăng ký lại.

## 6. Việc cần đọc hoặc quyết định trước khi viết code

1. Đọc `AdminAction`, `AuditActions`, `AdminActionRecorder`: ghi được sự kiện bảo mật tự phục vụ (actor là chính tài
   khoản) hay phải mở rộng/thêm bảng riêng. Nếu mở rộng thì làm trong cùng migration.
2. Đọc `TokenKeys`: thêm khoá dẫn xuất cho `mfa-pending` thế nào.
3. Chọn cột bump phiên (mục 1.9).
4. Chọn thư viện TOTP hoặc tự cài (mục 4).
5. Câu hỏi cho chủ sản phẩm: (a) ai được reset MFA của ADMIN và quy trình hai người ra sao; (b) quản lý khoá
   `MFA_ENCRYPTION_KEY` (biến môi trường hay KMS) và xoay khoá; (c) giai đoạn 2 có bắt buộc cho người dùng thường không;
   (d) có role moderator riêng không (hiện chỉ thấy ADMIN).

## 7. Triển khai và quay lại

1. Đưa backend lên với `shortvideo.mfa.enabled=false`: không đổi gì với ai.
2. Bật cờ; từng ADMIN đăng ký (3.1.3) và lưu mã khôi phục. Kiểm tra bằng `GET /auth/me` (thêm `mfa:true/false`).
3. Đặt `required-roles=ADMIN` và bật kiểm tra ở `/internal/**`. Phiên ADMIN cũ không có `amr` nên `/internal` trả
   `MFA_REQUIRED` cho tới khi đăng nhập lại (tối đa 12 giờ theo `privileged-absolute-ttl`, hoặc ngay nếu bạn thu hồi).
4. **Quay lại:** tắt `enabled` (bỏ ép ở `/internal`). Dữ liệu yếu tố giữ nguyên nên bật lại không mất gì.
5. Giữ một **tài khoản khẩn cấp** ngoài đường mạng thường (xem tài liệu về mTLS/VPN) để không bị khoá cả đội admin nếu
   mất thiết bị đồng loạt.

### Những thứ đã kiểm tra là không bị ảnh hưởng, và những thứ cần chỉnh

Đã xác minh trong code (2026-10-11):

- **Không ảnh hưởng:** mọi job `@Scheduled` (`OutboxRelay`, `OutboxCleanupJob`, `InboxCleanupJob`, `UploadCleanupJob`,
  `UploadCompletionReconciliationJob`, `FailedSourceCleanupJob`, `SupersededAssetCleanupJob`, `TranscodeWatchdog`,
  `EligibilityReconciliationJob`, `RevocationCacheRebuilder`), các Kafka consumer và media-worker. Không có cái nào
  đọc `SecurityContextHolder`/`AuthenticatedAccount`; chúng gọi service trong tiến trình, không qua đăng nhập hay
  `/internal`. Prometheus scrape `/actuator/prometheus` bằng token `METRICS_SCRAPER`: **chỉ ép MFA ở `/internal/**`,
  không mở rộng sang `/actuator/**`**. `seed.mjs`, `upload-poll-load.js`, `video-status-poll-load.js` dùng người dùng
  thường nên không bị.
- **Cần chỉnh khi bật MFA cho một tài khoản admin:**
  - `tests/k6/media-gateway-load.js` đăng nhập bằng `ADMIN_EMAIL` và mong cookie phiên; với admin có MFA, login trả
    `mfaRequired` và `setup()` hỏng. Script chỉ gọi `public-playback-session` (không cần quyền admin), nên đổi sang
    tài khoản thường.
  - `AuthorizationIT`, `ModerationPublicationFlowIT` (và `AccountFlowIT` có gọi `/internal`) đăng nhập admin rồi gọi
    `/internal`. Cờ mặc định tắt nên chạy như cũ; chỉ các test MFA mới bật cờ. Thêm một helper test dùng chung
    "đăng nhập admin có MFA" (cấp phiên có `amr`).
- **Quy tắc cho tương lai:** job hay công cụ ngoài gọi `/internal/**` bằng tài khoản admin (ví dụ cron) sẽ bị khoá.
  Hiện repo không có cái nào; nếu cần, dùng token dịch vụ có quyền hẹp giống `METRICS_SCRAPER`, không dùng tài khoản
  admin và không tắt MFA.

## 8. Ước lượng và thứ tự

Chưa có số đo; đây là ước lượng thô: giai đoạn 1 khoảng 3 đến 5 ngày làm việc kể cả test và giao diện đăng ký; giai đoạn 2
khoảng 2 đến 3 ngày; giai đoạn 3 (WebAuthn) khoảng 1,5 đến 2 lần giai đoạn 1.

Thứ tự trong giai đoạn 1: (1) mục 6.1 đến 6.4; (2) migration và `TotpCodec` với test vector; (3) `JwtService`/`amr` và
`mfa-pending`; (4) `MfaService` và endpoint; (5) giới hạn tốc độ fail-closed; (6) ép ở `SecurityConfig` sau cờ;
(7) admin-web; (8) tích hợp và thử trên trình duyệt; (9) triển khai theo mục 7.

## 9. Rủi ro

- **Khôi phục tài khoản là mắt xích yếu nhất.** Nếu "mất thiết bị" dễ hơn đăng nhập, kẻ tấn công sẽ đi đường đó. Vì
  vậy ADMIN không tự reset và không có reset qua API.
- **MFA không chặn trộm cookie phiên sau khi đăng nhập** (mã độc, XSS). Phần đó do `HttpOnly`, phiên ngắn, và mTLS lo
  (xem `infrastructure/nginx/README.md`); MFA và mTLS bổ sung cho nhau chứ không thay nhau.
- **Khoá `MFA_ENCRYPTION_KEY` mất là mất mọi bí mật TOTP** (mọi người phải đăng ký lại). Sao lưu khoá cùng cách với
  `JWT_SECRET`.
- **Lệch giờ máy chủ** làm mọi mã sai: đồng bộ NTP trên máy chủ; log số lần thất bại do lệch giờ nếu cần.
- **Bộ đếm Redis** là phụ thuộc mới cho bước đăng nhập của ADMIN; vì fail-closed, Redis hỏng nghĩa là admin không đăng
  nhập được. Chấp nhận có chủ ý; ghi vào runbook.
