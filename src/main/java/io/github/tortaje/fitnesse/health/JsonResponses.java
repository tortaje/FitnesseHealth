package io.github.tortaje.fitnesse.health;

import fitnesse.http.SimpleResponse;
import java.io.UnsupportedEncodingException;
import java.util.Map;

final class JsonResponses {

    private JsonResponses() {
    }

    static SimpleResponse of(int status, Map<String, Object> body) {
        SimpleResponse response = new SimpleResponse(status);
        response.setContentType("application/json; charset=utf-8");
        response.addHeader("Cache-Control", "no-store");
        try {
            response.setContent(Json.write(body));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return response;
    }
}
