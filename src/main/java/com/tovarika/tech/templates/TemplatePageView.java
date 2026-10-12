package com.tovarika.tech.templates;

import java.util.List;

public record TemplatePageView(List<TemplateView> items, int limit, String nextCursor) {}
