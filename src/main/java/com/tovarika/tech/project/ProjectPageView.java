package com.tovarika.tech.project;

import java.util.List;

record ProjectPageView(List<ProjectView> items, int limit, String nextCursor) {}
