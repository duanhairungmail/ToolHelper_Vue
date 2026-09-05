package com.toolhelper.infrastructure;

import java.util.List;

public record RuntimeProperties(String sessionToken, String internalToken, List<String> allowedOrigins) {}
