package com.group2.incident.telemetry.service;

import com.group2.incident.common.error.ServiceUnavailableException;
import com.group2.incident.telemetry.config.TelemetryProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts each org's Grafana read token with AES-256-GCM under {@code TELEMETRY_SECRET_KEY}, a base64 key of
 * 32 bytes that lives only in the environment. The org id is bound in as associated data, so a ciphertext
 * copied into another org's row does not decrypt. Without a key, connections can't be saved or used.
 */
@Component
public class TokenCipher {

	static final String NOT_SET_UP = "Telemetry connections are not set up on this server";

	private static final String PREFIX = "v1:";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKey key;

	private final SecureRandom random = new SecureRandom();

	public TokenCipher(TelemetryProperties properties) {
		this.key = parseKey(properties.secretKey());
	}

	public String encrypt(long orgId, String token) {
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(associatedData(orgId));
			byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
			return PREFIX + Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Could not encrypt the telemetry token", e);
		}
	}

	/** The token, or 503 when the key is missing or the value was not sealed for this org with this key. */
	public String decrypt(long orgId, String value) {
		SecretKey secret = requireKey();
		if (value == null || !value.startsWith(PREFIX)) {
			throw new ServiceUnavailableException(NOT_SET_UP);
		}
		try {
			byte[] in = Base64.getDecoder().decode(value.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			cipher.updateAAD(associatedData(orgId));
			return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			// A changed key, or a value from another org. Say neither which nor anything about the value.
			throw new ServiceUnavailableException("The saved telemetry token can't be read; save it again");
		}
	}

	private SecretKey requireKey() {
		if (key == null) {
			throw new ServiceUnavailableException(NOT_SET_UP);
		}
		return key;
	}

	private static byte[] associatedData(long orgId) {
		return ("telemetry-token:org:" + orgId).getBytes(StandardCharsets.UTF_8);
	}

	private static SecretKey parseKey(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(value.strip());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("TELEMETRY_SECRET_KEY must be base64");
		}
		if (bytes.length != 32) {
			throw new IllegalStateException("TELEMETRY_SECRET_KEY must be 32 bytes, base64 encoded");
		}
		return new SecretKeySpec(bytes, "AES");
	}
}
