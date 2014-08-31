package nxt;

import nxt.db.Db;
import nxt.db.DbIterator;
import nxt.db.DbKey;
import nxt.db.EntityDbTable;
import nxt.peer.Peer;
import nxt.peer.Peers;
import nxt.util.Convert;
import nxt.util.JSON;
import nxt.util.Listener;
import nxt.util.Listeners;
import nxt.util.Logger;
import nxt.util.ThreadPool;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class TransactionProcessorImpl implements TransactionProcessor {

    private static final TransactionProcessorImpl instance = new TransactionProcessorImpl();

    static TransactionProcessorImpl getInstance() {
        return instance;
    }

    private static final DbKey.LongKeyFactory<TransactionImpl> unconfirmedTransactionDbKeyFactory = new DbKey.LongKeyFactory<TransactionImpl>("id") {

        @Override
        public DbKey newKey(TransactionImpl transaction) {
            return newKey(transaction.getId());
        }

    };

    private static final EntityDbTable<TransactionImpl> unconfirmedTransactionTable = new EntityDbTable<TransactionImpl>(unconfirmedTransactionDbKeyFactory) {

        @Override
        protected TransactionImpl load(Connection con, ResultSet rs) throws SQLException {
            byte[] transactionBytes = rs.getBytes("transaction_bytes");
            try {
                return TransactionImpl.parseTransaction(transactionBytes);
            } catch (NxtException.ValidationException e) {
                throw new RuntimeException(e.toString(), e);
            }
        }

        @Override
        protected void save(Connection con, TransactionImpl transaction) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO unconfirmed_transaction (id, expiration, transaction_bytes) "
                    + "VALUES (?, ?, ?)")) {
                int i = 0;
                pstmt.setLong(++i, transaction.getId());
                pstmt.setInt(++i, transaction.getExpiration());
                pstmt.setBytes(++i, transaction.getBytes());
                pstmt.executeUpdate();
            }
        }

        @Override
        protected String table() {
            return "unconfirmed_transaction";
        }

        @Override
        protected String defaultSort() {
            return "";
        }

        @Override
        public void rollback(int height) {}

    };

    private final ConcurrentMap<Long, TransactionImpl> nonBroadcastedTransactions = new ConcurrentHashMap<>();
    private final Listeners<List<Transaction>,Event> transactionListeners = new Listeners<>();

    private final Runnable removeUnconfirmedTransactionsThread = new Runnable() {

        @Override
        public void run() {

            try {
                try {

                    int curTime = Convert.getEpochTime();
                    List<Transaction> removedUnconfirmedTransactions = new ArrayList<>();

                    synchronized (BlockchainImpl.getInstance()) {
                        try (Connection con = Db.beginTransaction();
                             PreparedStatement pstmt = con.prepareStatement("SELECT * FROM unconfirmed_transaction "
                                     + "WHERE expiration < ? FOR UPDATE", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                            pstmt.setInt(1, curTime);
                            try (ResultSet rs = pstmt.executeQuery()) {
                                while (rs.next()) {
                                    TransactionImpl transaction = TransactionImpl.parseTransaction(rs.getBytes("transaction_bytes"));
                                    rs.deleteRow();
                                    transaction.undoUnconfirmed();
                                    removedUnconfirmedTransactions.add(transaction);
                                }
                            }
                            Db.commitTransaction();
                        } catch (Exception e) {
                            Db.rollbackTransaction();
                            throw e;
                        } finally {
                            Db.endTransaction();
                        }
                    }

                    if (removedUnconfirmedTransactions.size() > 0) {
                        transactionListeners.notify(removedUnconfirmedTransactions, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error removing unconfirmed transactions", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable rebroadcastTransactionsThread = new Runnable() {

        @Override
        public void run() {

            try {
                try {
                    List<Transaction> transactionList = new ArrayList<>();
                    int curTime = Convert.getEpochTime();
                    for (TransactionImpl transaction : nonBroadcastedTransactions.values()) {
                        if (TransactionDb.hasTransaction(transaction.getId()) || transaction.getExpiration() < curTime) {
                            nonBroadcastedTransactions.remove(transaction.getId());
                        } else if (transaction.getTimestamp() < curTime - 30) {
                            transactionList.add(transaction);
                        }
                    }

                    if (transactionList.size() > 0) {
                        Peers.sendToSomePeers(transactionList);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error in transaction re-broadcasting thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable processTransactionsThread = new Runnable() {

        private final JSONStreamAware getUnconfirmedTransactionsRequest;
        {
            JSONObject request = new JSONObject();
            request.put("requestType", "getUnconfirmedTransactions");
            getUnconfirmedTransactionsRequest = JSON.prepareRequest(request);
        }

        @Override
        public void run() {
            try {
                try {
                    Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                    if (peer == null) {
                        return;
                    }
                    JSONObject response = peer.send(getUnconfirmedTransactionsRequest);
                    if (response == null) {
                        return;
                    }
                    JSONArray transactionsData = (JSONArray)response.get("unconfirmedTransactions");
                    if (transactionsData == null || transactionsData.size() == 0) {
                        return;
                    }
                    try {
                        processPeerTransactions(transactionsData, false);
                    } catch (NxtException.ValidationException|RuntimeException e) {
                        peer.blacklist(e);
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error processing unconfirmed transactions from peer", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }
        }

    };

    private TransactionProcessorImpl() {
        ThreadPool.scheduleThread(processTransactionsThread, 5);
        ThreadPool.scheduleThread(removeUnconfirmedTransactionsThread, 1);
        ThreadPool.scheduleThread(rebroadcastTransactionsThread, 60);
    }

    @Override
    public boolean addListener(Listener<List<Transaction>> listener, Event eventType) {
        return transactionListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<List<Transaction>> listener, Event eventType) {
        return transactionListeners.removeListener(listener, eventType);
    }

    @Override
    public DbIterator<TransactionImpl> getAllUnconfirmedTransactions() {
        return unconfirmedTransactionTable.getAll(0, -1);
    }

    @Override
    public Transaction getUnconfirmedTransaction(Long transactionId) {
        return unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(transactionId));
    }

    public Transaction.Builder newTransactionBuilder(byte[] senderPublicKey, long amountNQT, long feeNQT, short deadline,
                                                     Attachment attachment) throws NxtException.ValidationException {
        byte version = (byte) getTransactionVersion(Nxt.getBlockchain().getHeight());
        int timestamp = Convert.getEpochTime();
        TransactionImpl.BuilderImpl builder = new TransactionImpl.BuilderImpl(version, senderPublicKey, amountNQT, feeNQT, timestamp,
                deadline, (Attachment.AbstractAttachment)attachment);
        if (version > 0) {
            Block ecBlock = EconomicClustering.getECBlockId(timestamp);
            builder.ecBlockHeight(ecBlock.getHeight());
            builder.ecBlockId(ecBlock.getId());
        }
        return builder;
    }

    @Override
    public void broadcast(Transaction transaction) throws NxtException.ValidationException {
        if (! transaction.verifySignature()) {
            throw new NxtException.NotValidException("Transaction signature verification failed");
        }
        List<Transaction> validTransactions = processTransactions(Collections.singleton((TransactionImpl) transaction), true);
        if (validTransactions.contains(transaction)) {
            nonBroadcastedTransactions.put(transaction.getId(), (TransactionImpl) transaction);
            Logger.logDebugMessage("Accepted new transaction " + transaction.getStringId());
        } else {
            Logger.logDebugMessage("Rejecting double spending transaction " + transaction.getStringId());
            throw new NxtException.NotValidException("Double spending transaction");
        }
    }

    @Override
    public void processPeerTransactions(JSONObject request) throws NxtException.ValidationException {
        JSONArray transactionsData = (JSONArray)request.get("transactions");
        processPeerTransactions(transactionsData, true);
    }

    @Override
    public Transaction parseTransaction(byte[] bytes) throws NxtException.ValidationException {
        return TransactionImpl.parseTransaction(bytes);
    }

    @Override
    public TransactionImpl parseTransaction(JSONObject transactionData) throws NxtException.NotValidException {
        return TransactionImpl.parseTransaction(transactionData);
    }

    void clear() {
        nonBroadcastedTransactions.clear();
    }

    void undo(BlockImpl block) {
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        for (TransactionImpl transaction : block.getTransactions()) {
            transaction.undo();
            unconfirmedTransactionTable.insert(transaction);
            addedUnconfirmedTransactions.add(transaction);
        }
        if (addedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedUnconfirmedTransactions, TransactionProcessor.Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    void applyUnconfirmed(Set<Long> unapplied) {
        List<Transaction> removedUnconfirmedTransactions = new ArrayList<>();
        for (Long transactionId : unapplied) {
            TransactionImpl transaction = unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(transactionId));
            if (! transaction.applyUnconfirmed()) {
                unconfirmedTransactionTable.delete(transaction);
                removedUnconfirmedTransactions.add(transaction);
            }
        }
        if (removedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(removedUnconfirmedTransactions, TransactionProcessor.Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    Set<Long> undoAllUnconfirmed() {
        HashSet<Long> undone = new HashSet<>();
        try (DbIterator<TransactionImpl> transactions = unconfirmedTransactionTable.getAll(0, -1)) {
            while (transactions.hasNext()) {
                TransactionImpl transaction = transactions.next();
                transaction.undoUnconfirmed();
                undone.add(transaction.getId());
            }
        }
        return undone;
    }

    void updateUnconfirmedTransactions(BlockImpl block) {
        List<Transaction> addedConfirmedTransactions = new ArrayList<>();
        List<Transaction> removedUnconfirmedTransactions = new ArrayList<>();

        for (TransactionImpl transaction : block.getTransactions()) {
            addedConfirmedTransactions.add(transaction);
            TransactionImpl unconfirmedTransaction = unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(transaction.getId()));
            if (unconfirmedTransaction != null) {
                unconfirmedTransactionTable.delete(unconfirmedTransaction);
                removedUnconfirmedTransactions.add(unconfirmedTransaction);
            }
        }

        if (removedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(removedUnconfirmedTransactions, TransactionProcessor.Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
        if (addedConfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedConfirmedTransactions, TransactionProcessor.Event.ADDED_CONFIRMED_TRANSACTIONS);
        }

    }

    void removeUnconfirmedTransactions(Collection<TransactionImpl> transactions) {
        List<Transaction> removedList = new ArrayList<>();
        synchronized (BlockchainImpl.getInstance()) {
            try {
                Db.beginTransaction();
                for (TransactionImpl transaction : transactions) {
                    if (unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(transaction.getId())) != null) {
                        unconfirmedTransactionTable.delete(transaction);
                        transaction.undoUnconfirmed();
                        removedList.add(transaction);
                    }
                }
                Db.commitTransaction();
            } catch (Exception e) {
                Db.rollbackTransaction();
            } finally {
                Db.endTransaction();
            }
        }
        transactionListeners.notify(removedList, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
    }

    void shutdown() {
        //removeUnconfirmedTransactions(new ArrayList<>(unconfirmedTransactionTable.getAll()));
    }

    int getTransactionVersion(int previousBlockHeight) {
        return previousBlockHeight < Constants.DIGITAL_GOODS_STORE_BLOCK ? 0 : 1;
    }

    private void processPeerTransactions(JSONArray transactionsData, final boolean sendToPeers) throws NxtException.ValidationException {
        List<TransactionImpl> transactions = new ArrayList<>();
        for (Object transactionData : transactionsData) {
            try {
                TransactionImpl transaction = parseTransaction((JSONObject)transactionData);
                try {
                    transaction.validate();
                } catch (NxtException.NotCurrentlyValidException ignore) {}
                transactions.add(transaction);
            } catch (NxtException.NotValidException e) {
                Logger.logDebugMessage("Invalid transaction from peer: " + ((JSONObject) transactionData).toJSONString());
                throw e;
            }
        }
        processTransactions(transactions, sendToPeers);
        for (TransactionImpl transaction : transactions) {
            nonBroadcastedTransactions.remove(transaction.getId());
        }
    }

    List<Transaction> processTransactions(Collection<TransactionImpl> transactions, final boolean sendToPeers) {
        List<Transaction> sendToPeersTransactions = new ArrayList<>();
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        List<Transaction> addedDoubleSpendingTransactions = new ArrayList<>();

        for (TransactionImpl transaction : transactions) {

            try {

                int curTime = Convert.getEpochTime();
                if (transaction.getTimestamp() > curTime + 15 || transaction.getExpiration() < curTime
                        || transaction.getDeadline() > 1440) {
                    continue;
                }
                if (transaction.getVersion() < 1) {
                    continue;
                }

                synchronized (BlockchainImpl.getInstance()) {
                    try {
                        Db.beginTransaction();
                        if (Nxt.getBlockchainProcessor().isDownloading() || Nxt.getBlockchain().getHeight() < Constants.DIGITAL_GOODS_STORE_BLOCK) {
                            break; // not ready to process transactions
                        }

                        Long id = transaction.getId();
                        if (TransactionDb.hasTransaction(id) || unconfirmedTransactionTable.get(unconfirmedTransactionDbKeyFactory.newKey(id)) != null) {
                            continue;
                        }

                        if (! transaction.verifySignature()) {
                            if (Account.getAccount(transaction.getSenderId()) != null) {
                                Logger.logDebugMessage("Transaction " + transaction.getJSONObject().toJSONString() + " failed to verify");
                            }
                            continue;
                        }

                        if (transaction.applyUnconfirmed()) {
                            if (sendToPeers) {
                                if (nonBroadcastedTransactions.containsKey(id)) {
                                    Logger.logDebugMessage("Received back transaction " + transaction.getStringId()
                                            + " that we generated, will not forward to peers");
                                    nonBroadcastedTransactions.remove(id);
                                } else {
                                    sendToPeersTransactions.add(transaction);
                                }
                            }
                            unconfirmedTransactionTable.insert(transaction);
                            addedUnconfirmedTransactions.add(transaction);
                        } else {
                            addedDoubleSpendingTransactions.add(transaction);
                        }
                        Db.commitTransaction();
                    } catch (Exception e) {
                        Db.rollbackTransaction();
                        throw e;
                    } finally {
                        Db.endTransaction();
                    }
                }
            } catch (RuntimeException e) {
                Logger.logMessage("Error processing transaction", e);
            }

        }

        if (sendToPeersTransactions.size() > 0) {
            Peers.sendToSomePeers(sendToPeersTransactions);
        }

        if (addedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
        if (addedDoubleSpendingTransactions.size() > 0) {
            transactionListeners.notify(addedDoubleSpendingTransactions, Event.ADDED_DOUBLESPENDING_TRANSACTIONS);
        }
        return addedUnconfirmedTransactions;
    }

}
