public class Main {
    public static void main(String[] args) {
        boolean known = args.length == 3 && (args[0].equals("lines") || args[0].equals("highlight"));
        if (!known) {
            System.err.println("usage: Main lines|highlight A_PATH B_PATH");
            System.exit(2);
        }
        String command = args[0];
        String aPath = args[1];
        String bPath = args[2];
        // TODO: read both files as raw bytes (brief, Section 2), then print the listing.
    }
}
