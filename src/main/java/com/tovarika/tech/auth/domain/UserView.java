package com.tovarika.tech.auth.domain;

import java.util.Set;

public record UserView(UserAccount user, Set<AuthProvider> providers) {}
