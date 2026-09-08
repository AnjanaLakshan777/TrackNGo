package com.trackngo.auth.internal.service.google;

import com.trackngo.commons.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Verifies Google Sign-In ID tokens server-side using Google's tokeninfo
 * endpoint. This avoids needing the OAuth client secret at all - the mobile
 * app requests an ID token directly (implicit/PKCE flow), and the backend
 * only needs to confirm Google issued it for our client and that the email
 * is verified. No credentials of ours are involved in this call.
 */
@Service
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final String expectedClientId;

    /*
      Built with explicit timeouts. A bare `new RestTemplate()` uses Spring's
      default request factory, which has no connect or read timeout at all - so a
      Google endpoint that accepts the connection and then never answers would
      block this request thread indefinitely. On a container sized for one vCPU
      that is how a single slow dependency takes the whole API down: the threads
      are all parked waiting, and nothing else can be served.
    */
    private final RestTemplate restTemplate = timeoutBoundedRestTemplate();

    private static RestTemplate timeoutBoundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    public GoogleTokenVerifier(@Value("${google.client-id:}") String expectedClientId) {
        this.expectedClientId = expectedClientId;
    }

    public boolean isConfigured() {
        return expectedClientId != null && !expectedClientId.isBlank();
    }

    @SuppressWarnings("unchecked")
    public GoogleTokenInfo verify(String idToken) {
        if (!isConfigured()) {
            throw new BusinessException("Google sign-in is not configured on the server.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new BusinessException("Missing Google ID token.");
        }

        Map<String, Object> payload;
        try {
            payload = restTemplate.getForObject(TOKENINFO_URL + idToken, Map.class);
        } catch (HttpClientErrorException ex) {
            throw new BusinessException("Google sign-in failed. Please try again.");
        } catch (RestClientException ex) {
            log.error("Failed to reach Google tokeninfo endpoint", ex);
            throw new BusinessException("Could not verify Google sign-in right now. Please try again.");
        }

        if (payload == null) {
            throw new BusinessException("Google sign-in failed. Please try again.");
        }

        String audience = String.valueOf(payload.get("aud"));
        if (!expectedClientId.equals(audience)) {
            log.warn("Rejected Google ID token with unexpected audience: {}", audience);
            throw new BusinessException("Google sign-in failed. Please try again.");
        }

        boolean emailVerified = Boolean.parseBoolean(String.valueOf(payload.get("email_verified")));
        String email = (String) payload.get("email");
        if (email == null || email.isBlank() || !emailVerified) {
            throw new BusinessException("Your Google account email must be verified to sign in.");
        }

        return new GoogleTokenInfo(
                email,
                true,
                (String) payload.get("given_name"),
                (String) payload.get("family_name"),
                (String) payload.get("sub")
        );
    }
}
