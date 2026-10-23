package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ChallengeResponse(String challengeId, String type, Params params, Instant expiresAt) {

    public static final String TYPE_POW = "pow";
    public static final String TYPE_EMOJI = "emoji";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Params(String salt, Integer difficulty, String prompt, List<String> cells) {

        public static Params pow(String salt, int difficulty) {
            return new Params(salt, difficulty, null, null);
        }

        public static Params emoji(String prompt, List<String> cells) {
            return new Params(null, null, prompt, cells);
        }
    }
}
