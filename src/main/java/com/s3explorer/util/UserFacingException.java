package com.s3explorer.util;

/** An error whose message is already written for the user and can be shown as-is. */
public class UserFacingException extends RuntimeException {

    private final String title;

    public UserFacingException(String title, String message) {
        super(message);
        this.title = title;
    }

    public String title() {
        return title;
    }
}
