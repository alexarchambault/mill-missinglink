package com.example;

/**
 * A library class compiled against Lib v2 (which has newMethod).
 * At runtime only Lib v1 (no newMethod) is available — the conflict
 * missinglink should detect.
 */
public class LibAClass {
    public void run() {
        new Lib().newMethod(); // calls a method present in v2 but NOT in v1
    }
}
