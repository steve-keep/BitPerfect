
package com.google.mlkit.genai.generative;

public class GenerativeModel {
    public static boolean isAvailable(android.content.Context context) { return true; }
    public static GenerativeModel getInstance(android.content.Context context) { return new GenerativeModel(); }
    public Response generateContent(String prompt) { return new Response(); }

    public static class Response {
        public String getText() { return "[]"; }
    }
}
