package com.example;

/** Our project entry point — calls into libA, making LibAClass reachable. */
public class App {
    public static void main(String[] args) {
        new LibAClass().run();
    }
}
