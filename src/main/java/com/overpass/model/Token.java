package com.overpass.model;

@lombok.Data
public class Token {

    private boolean success;
    private Data data;

    @lombok.Data
    public static class Data {
        private String idToken;
        private String refreshToken;
    }
}
