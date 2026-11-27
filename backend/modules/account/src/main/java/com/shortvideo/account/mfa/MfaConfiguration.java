package com.shortvideo.account.mfa;

import com.shortvideo.shared.security.JwtProperties;
import com.shortvideo.shared.security.MfaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "shortvideo.mfa", name = "enabled", havingValue = "true")
class MfaConfiguration {

    /** Fails the start-up when the factor is on and the key is missing, malformed or the published placeholder. */
    @Bean
    MfaSecretCipher mfaSecretCipher(MfaProperties mfa, JwtProperties jwt) {
        return new MfaSecretCipher(mfa.getEncryptionKey(), mfa.getEncryptionKeyId(), jwt.isAllowInsecureSecret());
    }
}
