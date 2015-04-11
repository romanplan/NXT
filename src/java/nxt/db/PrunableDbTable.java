package nxt.db;

import nxt.Nxt;
import nxt.util.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public abstract class PrunableDbTable<T> extends PersistentDbTable<T> {

    protected PrunableDbTable(String table, DbKey.Factory<T> dbKeyFactory) {
        super(table, dbKeyFactory);
    }

    protected PrunableDbTable(String table, DbKey.Factory<T> dbKeyFactory, String fullTextSearchColumns) {
        super(table, dbKeyFactory, fullTextSearchColumns);
    }

    @Override
    public void trim(int height) {
        super.trim(height);
        try (Connection con = db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("DELETE FROM " + table + " WHERE expiration < ?")) {
            pstmt.setInt(1, Nxt.getEpochTime());
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                Logger.logDebugMessage("Deleted " + deleted + " expired prunable data from " + table);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }

    }

}
