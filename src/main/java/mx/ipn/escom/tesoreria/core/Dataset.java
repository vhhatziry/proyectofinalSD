package mx.ipn.escom.tesoreria.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the shared account dataset into a {@link Ledger}. Every node reads the
 * very same file so they all start from an identical set of balances, which is
 * what makes the conservation-of-money check across the cluster meaningful.
 *
 * <p>The file is plain CSV with four columns and no header row:
 * {@code firstName,lastName1,lastName2,balance}. There is no id column. Each
 * account's id is simply its position in the file counting from one, so the
 * first data line becomes account 1; the first line must therefore be parsed,
 * not skipped. The owner name is the three name fields joined with spaces, and
 * the balance is parsed from its decimal text into integer cents through
 * {@link Money#toCents(String)} so no floating point ever touches a balance.
 */
public final class Dataset {

    /** First account id; ids are assigned sequentially from this base. */
    private static final int FIRST_ID = 1;

    private Dataset() {
    }

    /**
     * Reads every line of {@code csv}, building one {@link Account} per row and
     * registering it with {@code ledger}.
     *
     * @return the number of accounts loaded
     * @throws IOException if the file cannot be read
     */
    public static int loadInto(Ledger ledger, Path csv) throws IOException {
        int id = FIRST_ID;
        int loaded = 0;
        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                if (cols.length < 4) {
                    continue;
                }
                String owner = cols[0].trim() + " " + cols[1].trim() + " " + cols[2].trim();
                long cents = Money.toCents(cols[3].trim());
                ledger.add(new Account(id, owner, cents));
                id++;
                loaded++;
            }
        }
        return loaded;
    }
}
