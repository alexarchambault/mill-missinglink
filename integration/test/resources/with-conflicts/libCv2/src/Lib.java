package com.example;

/** Library version 2 — compile-time version, adds `newMethod()`. */
public class Lib {
    public void hello() {
        System.out.println("hello from lib v2");
    }

    public void newMethod() {
        System.out.println("new method, only in v2");
    }
}
