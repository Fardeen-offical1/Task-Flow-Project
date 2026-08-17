package com.taskmgmt.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.taskmgmt.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.List;

/**
 * Issues short-lived, locally-signed JWTs (HS256) after AuthController
 * verifies the user's password. No external identity provider is part
 * of this deliverable's architecture (see the diagram — clients talk
 * directly to the Java backend), so we sign/verify with a shared
 * secret instead of doing OIDC discovery against a service that
 * doesn't exist in this stack. SecurityConfig's JwtDecoder verifies
 * tokens issued here using the same secret.
 */
@Service
public class JwtService {

    private final byte[] secret;
    private final long expiryMillis;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiry-minutes:60}") long expiryMinutes) {
        this.secret = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.expiryMillis = expiryMinutes * 60_000;
    }

    public String issueToken(User user) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user.getId().toString())
                    .claim("email", user.getEmail())
                    .claim("roles", List.of(user.getRole().getName()))
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + expiryMillis))
                    .build();

            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signedJWT.sign(new MACSigner(secret));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue JWT", e);
        }
    }
}
