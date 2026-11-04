package com.soma.wes.auth.dto


data class OAuthRegistrationDto(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val authorizationUri: String,
    val tokenUri: String,
    val userInfoUri: String,
    val scopes: Set<String>,
)
