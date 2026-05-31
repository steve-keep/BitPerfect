import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) {
        String hex = "5465737420417274697374";
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        System.out.println(bytes.toString());
        System.out.println(new String(bytes, StandardCharsets.UTF_8));
    }
}
