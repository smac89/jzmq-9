package org.zeromq.core;

import java.util.Objects;

public class Utils {

    private Utils() {
    }

    public static void checkNotNull(Object obj) {
        Objects.requireNonNull(obj, "Argument must not be null");
    }
}
