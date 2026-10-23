package com.akbank.analytics.applications.bo.model;

/** Kullanıcı adı + şifre. toString şifreyi asla göstermez. */
public final class Credential {
    private final String username;
    private final String password;

    public Credential(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() { return username; }
    public String getPassword() { return password; }

    @Override
    public String toString() {
        return "kullanici=" + username + ", sifre=****(" + password.length() + " karakter)";
    }
}
