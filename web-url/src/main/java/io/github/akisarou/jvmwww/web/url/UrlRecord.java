package io.github.akisarou.jvmwww.web.url;

import java.util.ArrayList;

/** Mutable internal record for the selected special HTTP(S) URL profile. */
final class UrlRecord {
    String scheme;
    String username;
    String password;
    String host;
    int port;
    final ArrayList<String> path;
    String query;
    String fragment;

    UrlRecord() {
        username = "";
        password = "";
        port = -1;
        path = new ArrayList<String>();
    }

    UrlRecord(UrlRecord source) {
        scheme = source.scheme;
        username = source.username;
        password = source.password;
        host = source.host;
        port = source.port;
        path = new ArrayList<String>(source.path);
        query = source.query;
        fragment = source.fragment;
    }
}
