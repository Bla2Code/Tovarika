package com.tovarika.tech.project;

record ProjectOwner(String userId, String trialSessionId) {
    static ProjectOwner user(String id) {
        return new ProjectOwner(id, null);
    }

    static ProjectOwner trial(String id) {
        return new ProjectOwner(null, id);
    }
}
