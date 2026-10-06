import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class Main {

    static final int OP_KEEP_RANGE = 0; 
    static final int OP_DELETE = 1;     
    static final int OP_INSERT = 2;    

    
    static class LineFile {
        final byte[] data;
        final int[] start;
        final int[] length;
        final long[] hash;
        final int count;

        LineFile(byte[] data) {
            this.data = data;
            if (data.length == 0) {
                this.start = new int[0];
                this.length = new int[0];
                this.hash = new long[0];
                this.count = 0;
                return;
            }

    
            int n = 0;
            for (byte b : data) {
                if (b == '\n') n++;
            }
            if (data[data.length - 1] != '\n') n++;

            this.start = new int[n];
            this.length = new int[n];
            this.hash = new long[n];
            this.count = n;

            int lineIdx = 0;
            int lineStart = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    start[lineIdx] = lineStart;
                    length[lineIdx] = i - lineStart;
                    lineIdx++;
                    lineStart = i + 1;
                }
            }
            if (lineStart < data.length) {
                start[lineIdx] = lineStart;
                length[lineIdx] = data.length - lineStart;
                lineIdx++;
            }

            for (int i = 0; i < n; i++) {
                long h = 0xcbf29ce484222325L;
                int s = start[i];
                int len = length[i];
                for (int j = 0; j < len; j++) {
                    h ^= (data[s + j] & 0xFF);
                    h *= 0x100000001b3L;
                }
                hash[i] = h;
            }
        }

        boolean equalsLine(int aIdx, LineFile other, int bIdx) {
            if (this.length[aIdx] != other.length[bIdx]) return false;
            if (this.hash[aIdx] != other.hash[bIdx]) return false;
            return Arrays.equals(this.data, this.start[aIdx], this.start[aIdx] + this.length[aIdx],
                                 other.data, other.start[bIdx], other.start[bIdx] + other.length[bIdx]);
        }
    }

    static class Operation {
        final int type;
        final int aIdx;
        final int bIdx;
        final int count;

        Operation(int type, int aIdx, int bIdx, int count) {
            this.type = type;
            this.aIdx = aIdx;
            this.bIdx = bIdx;
            this.count = count;
        }
    }

    @FunctionalInterface
    interface EqualityChecker {
        boolean equals(int aIdx, int bIdx);
    }

    public static void main(String[] args) {
        if (args.length != 3 || (!args[0].equals("lines") && !args[0].equals("highlight"))) {
            System.err.println("usage: Main lines|highlight A_PATH B_PATH");
            System.exit(2);
        }

        String command = args[0];
        String aPath = args[1];
        String bPath = args[2];

        byte[] aBytes;
        byte[] bBytes;
        try {
            aBytes = Files.readAllBytes(Path.of(aPath));
            bBytes = Files.readAllBytes(Path.of(bPath));
        } catch (Exception e) {
            System.err.println("Error reading input files: " + e.getMessage());
            System.exit(2);
            return;
        }

        LineFile fileA = new LineFile(aBytes);
        LineFile fileB = new LineFile(bBytes);

        List<Operation> lineOperations = runMyers(fileA.count, fileB.count, (i, j) -> fileA.equalsLine(i, fileB, j));

        boolean isHighlight = command.equals("highlight");
        try (OutputStream out = new BufferedOutputStream(System.out, 65536)) {
            List<Integer> deletes = new ArrayList<>();
            List<Integer> inserts = new ArrayList<>();

            for (Operation op : lineOperations) {
                if (op.type == OP_KEEP_RANGE) {
                    flushChangeBlock(out, fileA, fileB, deletes, inserts, isHighlight);
                    for (int i = 0; i < op.count; i++) {
                        int aIdx = op.aIdx + i;
                        out.write(' ');
                        out.write(fileA.data, fileA.start[aIdx], fileA.length[aIdx]);
                        out.write('\n');
                    }
                } else if (op.type == OP_DELETE) {
                    deletes.add(op.aIdx);
                } else if (op.type == OP_INSERT) {
                    inserts.add(op.bIdx);
                }
            }
            flushChangeBlock(out, fileA, fileB, deletes, inserts, isHighlight);
            out.flush();
        } catch (IOException e) {
            System.err.println("Error writing output: " + e.getMessage());
        }
    }


    static void flushChangeBlock(OutputStream out, LineFile fileA, LineFile fileB,
                                 List<Integer> deletes, List<Integer> inserts,
                                 boolean isHighlight) throws IOException {
        if (deletes.isEmpty() && inserts.isEmpty()) {
            return;
        }

        for (int aIdx : deletes) {
            out.write('-');
            out.write(fileA.data, fileA.start[aIdx], fileA.length[aIdx]);
            out.write('\n');
        }

        for (int k = 0; k < inserts.size(); k++) {
            int bIdx = inserts.get(k);
            out.write('+');
            out.write(fileB.data, fileB.start[bIdx], fileB.length[bIdx]);
            out.write('\n');

            if (isHighlight && k < deletes.size()) {
                int aIdx = deletes.get(k);
                String hl = computeHighlight(fileA, aIdx, fileB, bIdx);
                out.write(hl.getBytes(StandardCharsets.UTF_8));
            }
        }

        deletes.clear();
        inserts.clear();
    }

    static String computeHighlight(LineFile fileA, int aIdx, LineFile fileB, int bIdx) {
        String strA = new String(fileA.data, fileA.start[aIdx], fileA.length[aIdx], StandardCharsets.UTF_8);
        String strB = new String(fileB.data, fileB.start[bIdx], fileB.length[bIdx], StandardCharsets.UTF_8);
        int[] cpA = strA.codePoints().toArray();
        int[] cpB = strB.codePoints().toArray();

        List<Operation> charOps = runMyers(cpA.length, cpB.length, (i, j) -> cpA[i] == cpB[j]);
        List<Integer> delIndices = new ArrayList<>();
        List<Integer> insIndices = new ArrayList<>();
        for (Operation op : charOps) {
            if (op.type == OP_DELETE) {
                delIndices.add(op.aIdx);
            } else if (op.type == OP_INSERT) {
                insIndices.add(op.bIdx);
            }
        }
        String oldRanges = formatRanges(delIndices);
        String newRanges = formatRanges(insIndices);
        return "? " + oldRanges + " | " + newRanges + "\n";
    }

    static String formatRanges(List<Integer> indices) {
        if (indices.isEmpty()) {
            return ".";
        }
        StringBuilder sb = new StringBuilder();
        int n = indices.size();
        int i = 0;
        while (i < n) {
            int start = indices.get(i);
            int end = start + 1;
            while (i + 1 < n && indices.get(i + 1) == end) {
                end++;
                i++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(start).append('-').append(end);
            i++;
        }
        return sb.toString();
    }

    static List<Operation> runMyers(int lenA, int lenB, EqualityChecker eq) {
        List<Operation> operations = new ArrayList<>();

        // 1. Common prefix trimming
        int prefix = 0;
        while (prefix < lenA && prefix < lenB && eq.equals(prefix, prefix)) {
            prefix++;
        }
        if (prefix > 0) {
            operations.add(new Operation(OP_KEEP_RANGE, 0, 0, prefix));
        }

        // 2. Common suffix trimming
        int suffix = 0;
        while (suffix < lenA - prefix && suffix < lenB - prefix &&
               eq.equals(lenA - 1 - suffix, lenB - 1 - suffix)) {
            suffix++;
        }

        int midA = lenA - prefix - suffix;
        int midB = lenB - prefix - suffix;

        if (midA == 0 && midB == 0) {
            // All elements matched in prefix/suffix
        } else if (midA == 0) {
            // Sequence A is fully matched; remaining elements of B are insertions
            for (int j = 0; j < midB; j++) {
                operations.add(new Operation(OP_INSERT, -1, prefix + j, 0));
            }
        } else if (midB == 0) {
            // Sequence B is fully matched; remaining elements of A are deletions
            for (int i = 0; i < midA; i++) {
                operations.add(new Operation(OP_DELETE, prefix + i, -1, 0));
            }
        } else {
            // 3. Myers O(ND) greedy search on the differing middle section
            int MAX = midA + midB;
            int offset = MAX + 1;
            int[] v = new int[2 * MAX + 3];
            Arrays.fill(v, -1);
            v[offset + 1] = 0;

            // trace stores only the active d + 1 elements at each step d (compact memory)
            List<int[]> trace = new ArrayList<>();

            // Initial snake at d = 0
            int x0 = 0;
            int y0 = 0;
            while (x0 < midA && y0 < midB && eq.equals(prefix + x0, prefix + y0)) {
                x0++;
                y0++;
            }
            v[offset + 0] = x0;
            trace.add(new int[] { x0 });

            int D = 0;
            int finalK = 0;
            if (x0 == midA && y0 == midB) {
                D = 0;
            } else {
                for (int d = 1; d <= MAX; d++) {
                    int[] vStep = new int[d + 1];
                    for (int k = -d; k <= d; k += 2) {
                        int idx = (k + d) >> 1;
                        boolean down;
                        if (k == -d) {
                            down = true;
                        } else if (k == d) {
                            down = false;
                        } else {
                            down = (v[offset + k - 1] < v[offset + k + 1]);
                        }

                        int x = down ? v[offset + k + 1] : (v[offset + k - 1] + 1);
                        int y = x - k;

                        // Follow snake (diagonal matches)
                        while (x < midA && y < midB && eq.equals(prefix + x, prefix + y)) {
                            x++;
                            y++;
                        }
                        v[offset + k] = x;
                        vStep[idx] = x;

                        if (x >= midA && y >= midB) {
                            D = d;
                            finalK = k;
                            trace.add(vStep);
                            break;
                        }
                    }
                    if (D > 0) {
                        break;
                    }
                    trace.add(vStep);
                }
            }

            // 4. Backtrack to reconstruct the shortest edit script
            List<Operation> midOps = new ArrayList<>();
            int currX = midA;
            int currY = midB;
            int currK = finalK;

            for (int d = D; d >= 1; d--) {
                int k = currK;
                boolean down;
                if (k == -d) {
                    down = true;
                } else if (k == d) {
                    down = false;
                } else {
                    int idxMinus = (k + d - 2) >> 1;
                    int idxPlus = (k + d) >> 1;
                    down = (trace.get(d - 1)[idxMinus] < trace.get(d - 1)[idxPlus]);
                }

                int prevK = down ? (k + 1) : (k - 1);
                int prevIdx = (prevK + (d - 1)) >> 1;
                int prevX = trace.get(d - 1)[prevIdx];
                int prevY = prevX - prevK;

                if (down) {
                    // Vertical step from (prevX, prevY) to (prevX, prevY + 1)
                    // followed by snake to (currX, currY)
                    int snakeLen = currX - prevX;
                    if (snakeLen > 0) {
                        midOps.add(new Operation(OP_KEEP_RANGE, prefix + prevX, prefix + prevY + 1, snakeLen));
                    }
                    midOps.add(new Operation(OP_INSERT, -1, prefix + prevY, 0));
                } else {
                    // Horizontal step from (prevX, prevY) to (prevX + 1, prevY)
                    // followed by snake to (currX, currY)
                    int snakeLen = currX - (prevX + 1);
                    if (snakeLen > 0) {
                        midOps.add(new Operation(OP_KEEP_RANGE, prefix + prevX + 1, prefix + prevY, snakeLen));
                    }
                    midOps.add(new Operation(OP_DELETE, prefix + prevX, -1, 0));
                }

                currX = prevX;
                currY = prevY;
                currK = prevK;
            }

            // Initial snake from (0, 0) to (currX, currY) at d = 0
            if (currX > 0) {
                midOps.add(new Operation(OP_KEEP_RANGE, prefix, prefix, currX));
            }

            Collections.reverse(midOps);
            operations.addAll(midOps);
        }

        if (suffix > 0) {
            operations.add(new Operation(OP_KEEP_RANGE, lenA - suffix, lenB - suffix, suffix));
        }

        return operations;
    }
}
