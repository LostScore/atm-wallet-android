package com.breadwallet.wallet.wallets.etherium;

import android.content.Context;
import android.util.Log;

import com.breadwallet.core.ethereum.BREthereumAmount;
import com.breadwallet.core.ethereum.BREthereumToken;
import com.breadwallet.core.ethereum.BREthereumTransaction;
import com.breadwallet.core.ethereum.BREthereumWallet;
import com.breadwallet.presenter.entities.CurrencyEntity;
import com.breadwallet.presenter.entities.TxUiHolder;
import com.breadwallet.tools.manager.BRReportsManager;
import com.breadwallet.tools.manager.BRSharedPrefs;
import com.breadwallet.tools.sqlite.CurrencyDataSource;
import com.breadwallet.tools.threads.executor.BRExecutor;
import com.breadwallet.tools.util.BRConstants;
import com.breadwallet.tools.util.Utils;
import com.breadwallet.wallet.abstracts.BaseWalletManager;
import com.breadwallet.wallet.abstracts.OnBalanceChangedListener;
import com.breadwallet.wallet.abstracts.OnTxListModified;
import com.breadwallet.wallet.abstracts.OnTxStatusUpdatedListener;
import com.breadwallet.wallet.abstracts.SyncListener;
import com.breadwallet.wallet.configs.WalletSettingsConfiguration;
import com.breadwallet.wallet.configs.WalletUiConfiguration;
import com.breadwallet.wallet.wallets.CryptoAddress;
import com.breadwallet.wallet.wallets.CryptoTransaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BreadWallet
 * <p/>
 * Created by Mihail Gutan on <mihail@breadwallet.com> 4/13/18.
 * Copyright (c) 2018 breadwallet LLC
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
public class WalletTokenManager implements BaseWalletManager {

    private static final String TAG = WalletTokenManager.class.getSimpleName();

    private WalletEthManager mWalletEthManager;
    private static Map<String, String> mTokenIsos = new HashMap<>();
    private static Map<String, WalletTokenManager> mTokenWallets = new HashMap<>();
    private BREthereumWallet mWalletToken;

    private List<OnBalanceChangedListener> balanceListeners = new ArrayList<>();
    private List<OnTxStatusUpdatedListener> txStatusUpdatedListeners = new ArrayList<>();
    private List<SyncListener> syncListeners = new ArrayList<>();
    private List<OnTxListModified> txModifiedListeners = new ArrayList<>();
    private WalletUiConfiguration uiConfig;

    private WalletTokenManager(WalletEthManager walletEthManager, BREthereumWallet tokenWallet) {
        this.mWalletEthManager = walletEthManager;
        this.mWalletToken = tokenWallet;
        uiConfig = new WalletUiConfiguration(tokenWallet.getToken().getColorLeft(), tokenWallet.getToken().getColorRight(), false);

    }

    private synchronized static WalletTokenManager getTokenWallet(WalletEthManager walletEthManager, BREthereumToken token) {
        if (mTokenWallets.containsKey(token.getAddress().toLowerCase())) {
            return mTokenWallets.get(token.getAddress().toLowerCase());
        } else {
            BREthereumWallet w = walletEthManager.node.getWallet(token);

            if (w != null) {
                w.setDefaultUnit(BREthereumAmount.Unit.TOKEN_DECIMAL);
                w.estimateGasPrice();
                if (w.getToken() == null) {
                    BRReportsManager.reportBug(new NullPointerException("getToken is null:" + token.getAddress()));
                    return null;
                }
                WalletTokenManager wm = new WalletTokenManager(walletEthManager, w);
                mTokenWallets.put(w.getToken().getAddress().toLowerCase(), wm);
                return wm;
            } else {
                BRReportsManager.reportBug(new NullPointerException("Failed to find token by address: " + token.getAddress()), true);
            }

        }
        return null;
    }


    //for testing only
    public static WalletTokenManager getBrdWallet(WalletEthManager walletEthManager) {
        BREthereumWallet brdWallet = walletEthManager.node.getWallet(BREthereumToken.tokenBRD);
        if (brdWallet.getToken() == null) {
            BRReportsManager.reportBug(new NullPointerException("getBrd failed"));
            return null;
        }
        return new WalletTokenManager(walletEthManager, brdWallet);
    }

    public synchronized static WalletTokenManager getTokenWalletByIso(WalletEthManager walletEthManager, String iso) {
        if (mTokenIsos.size() <= 0) mapTokenIsos();

        String address = mTokenIsos.get(iso.toLowerCase());
        address = address == null ? null : address.toLowerCase();
        if (address == null) {
            BRReportsManager.reportBug(new NullPointerException("getTokenWalletByIso: address is null for: " + iso));
            return null;
        }
        if (mTokenWallets.containsKey(address))
            return mTokenWallets.get(address);

        BREthereumToken token = BREthereumToken.lookup(address);
        if (token != null) {
            return getTokenWallet(walletEthManager, token);
        } else
            BRReportsManager.reportBug(new NullPointerException("Failed to getTokenWalletByIso: " + iso + ":" + address));
        return null;
    }

    public synchronized static void mapTokenIsos() {
        for (BREthereumToken t : BREthereumToken.tokens) {
            if (!mTokenIsos.containsKey(t.getSymbol().toLowerCase())) {
                mTokenIsos.put(t.getSymbol().toLowerCase(), t.getAddress().toLowerCase());
            }
        }
    }

    @Override
    public int getForkId() {
        //no need for Tokens
        return -1;
    }

    @Override
    public BREthereumAmount.Unit getUnit() {
        return BREthereumAmount.Unit.TOKEN_DECIMAL;
    }

    @Override
    public boolean isAddressValid(String address) {
        return mWalletEthManager.isAddressValid(address);
    }

    @Override
    public byte[] signAndPublishTransaction(CryptoTransaction tx, byte[] seed) {
        mWalletToken.sign(tx.getEtherTx(), new String(seed));
        mWalletToken.submit(tx.getEtherTx());
        String hash = tx.getEtherTx().getHash();
        return hash == null ? new byte[0] : hash.getBytes();
    }

    @Override
    public void addBalanceChangedListener(OnBalanceChangedListener listener) {
        if (listener != null && !balanceListeners.contains(listener))
            balanceListeners.add(listener);
    }

    @Override
    public void addTxStatusUpdatedListener(OnTxStatusUpdatedListener list) {
        if (list != null && !txStatusUpdatedListeners.contains(list))
            txStatusUpdatedListeners.add(list);
    }

    @Override
    public void addSyncListeners(SyncListener list) {
        if (list != null && !syncListeners.contains(list))
            syncListeners.add(list);
    }

    @Override
    public void addTxListModifiedListener(OnTxListModified list) {
        if (list != null && !txModifiedListeners.contains(list))
            txModifiedListeners.add(list);
    }

    @Override
    public long getRelayCount(byte[] txHash) {
        return 3; // ready to go
    }

    @Override
    public double getSyncProgress(long startHeight) {
        return mWalletEthManager.getSyncProgress(startHeight);
    }

    @Override
    public double getConnectStatus() {
        return mWalletEthManager.getConnectStatus();
    }

    @Override
    public void connect(Context app) {
        //no need for Tokens
    }

    @Override
    public void disconnect(Context app) {
        //no need for Tokens
    }

    @Override
    public boolean useFixedNode(String node, int port) {
        //no need for tokens
        return false;
    }

    @Override
    public void rescan() {
        //no need for tokens
    }

    @Override
    public CryptoTransaction[] getTxs(Context app) {
        BREthereumTransaction[] txs = mWalletToken.getTransactions();
        CryptoTransaction[] arr = new CryptoTransaction[txs.length];
        for (int i = 0; i < txs.length; i++) {
            arr[i] = new CryptoTransaction(txs[i]);
        }

        return arr;
    }

    @Override
    public BigDecimal getTxFee(CryptoTransaction tx) {
        return new BigDecimal(tx.getEtherTx().getFee(BREthereumAmount.Unit.ETHER_WEI));
    }

    @Override
    public BigDecimal getEstimatedFee(BigDecimal amount, String address) {
        BigDecimal fee;
        if (amount == null) return null;
        if (amount.compareTo(new BigDecimal(0)) == 0) {
            fee = new BigDecimal(0);
        } else {
            fee = new BigDecimal(mWalletToken.transactionEstimatedFee(amount.toPlainString(),
                    BREthereumAmount.Unit.ETHER_WEI, BREthereumAmount.Unit.ETHER_WEI));
        }
        return fee;
    }

    @Override
    public BigDecimal getFeeForTransactionSize(BigDecimal size) {
        return null;
    }

    @Override
    public String getTxAddress(CryptoTransaction tx) {
        return mWalletEthManager.getTxAddress(tx);
    }

    @Override
    public BigDecimal getMaxOutputAmount(Context app) {
        return mWalletEthManager.getMaxOutputAmount(app);
    }

    @Override
    public BigDecimal getMinOutputAmount(Context app) {
        return new BigDecimal(0);
    }

    @Override
    public BigDecimal getTransactionAmount(CryptoTransaction tx) {
        return mWalletEthManager.getTransactionAmount(tx);
    }

    @Override
    public BigDecimal getMinOutputAmountPossible() {
        return mWalletEthManager.getMinOutputAmountPossible();
    }

    @Override
    public void updateFee(Context app) {
        //no need
    }

    @Override
    public void refreshAddress(Context app) {
        if (Utils.isNullOrEmpty(BRSharedPrefs.getReceiveAddress(app, getIso(app)))) {
            String address = getReceiveAddress(app).stringify();
            if (Utils.isNullOrEmpty(address)) {
                Log.e(TAG, "refreshAddress: WARNING, retrieved address:" + address);
                BRReportsManager.reportBug(new NullPointerException("empty address!"));
            }
            BRSharedPrefs.putReceiveAddress(app, address, getIso(app));
        }
    }

    @Override
    public void refreshCachedBalance(final Context app) {
        BRExecutor.getInstance().forLightWeightBackgroundTasks().execute(new Runnable() {
            @Override
            public void run() {
                BigDecimal balance = new BigDecimal(mWalletToken.getBalance(BREthereumAmount.Unit.TOKEN_DECIMAL));
                BRSharedPrefs.putCachedBalance(app, getIso(app), balance);
            }
        });
    }

    @Override
    public List<TxUiHolder> getTxUiHolders(Context app) {
        BREthereumTransaction txs[] = mWalletToken.getTransactions();
        int blockHeight = (int) mWalletEthManager.node.getBlockHeight();
        if (app != null && blockHeight != Integer.MAX_VALUE && blockHeight > 0) {
            BRSharedPrefs.putLastBlockHeight(app, getIso(app), blockHeight);
        }
        if (txs == null || txs.length <= 0) return null;
        List<TxUiHolder> uiTxs = new ArrayList<>();
        for (int i = txs.length - 1; i >= 0; i--) { //revere order
            BREthereumTransaction tx = txs[i];
//            printTxInfo(tx);
            uiTxs.add(new TxUiHolder(tx, tx.getTargetAddress().equalsIgnoreCase(mWalletEthManager.getReceiveAddress(app).stringify()),
                    tx.getBlockTimestamp(), (int) tx.getBlockNumber(), Utils.isNullOrEmpty(tx.getHash()) ? null :
                    tx.getHash().getBytes(), tx.getHash(), new BigDecimal(tx.getFee(BREthereumAmount.Unit.ETHER_GWEI)), tx,
                    tx.getTargetAddress(), tx.getSourceAddress(), null, 0,
                    new BigDecimal(tx.getAmount(getUnit())), true));
        }

        return uiTxs;
    }

    @Override
    public boolean containsAddress(String address) {
        return mWalletEthManager.containsAddress(address);
    }

    @Override
    public boolean addressIsUsed(String address) {
        return mWalletEthManager.addressIsUsed(address);
    }

    @Override
    public boolean generateWallet(Context app) {
        return false;
    }

    @Override
    public String getSymbol(Context app) {
        return mWalletToken.getToken().getSymbol();
    }

    @Override
    public String getIso(Context app) {
        return mWalletToken.getToken().getSymbol();
    }

    @Override
    public String getScheme(Context app) {
        return null;
    }

    @Override
    public String getName(Context app) {
        return mWalletToken.getToken().getName();
    }

    @Override
    public String getDenomination(Context app) {
        return null;
    }

    @Override
    public CryptoAddress getReceiveAddress(Context app) {
        return mWalletEthManager.getReceiveAddress(app);
    }

    @Override
    public CryptoTransaction createTransaction(BigDecimal amount, String address) {
        BREthereumTransaction tx = mWalletToken.createTransaction(address, amount.toPlainString(), BREthereumAmount.Unit.TOKEN_DECIMAL);
        return new CryptoTransaction(tx);
    }

    @Override
    public String decorateAddress(Context app, String addr) {
        return addr;
    }

    @Override
    public String undecorateAddress(Context app, String addr) {
        return addr;
    }

    @Override
    public int getMaxDecimalPlaces(Context app) {
        return 5;
    }

    @Override
    public BigDecimal getCachedBalance(Context app) {
        return BRSharedPrefs.getCachedBalance(app, getIso(app));
    }

    @Override
    public BigDecimal getTotalSent(Context app) {
        return mWalletEthManager.getTotalSent(app);
    }

    @Override
    public void wipeData(Context app) {
        //Not needed for Tokens
    }

    @Override
    public void syncStarted() {
        //Not needed for Tokens
    }

    @Override
    public void syncStopped(String error) {
        //Not needed for Tokens
    }

    @Override
    public boolean networkIsReachable() {
        return mWalletEthManager.networkIsReachable();
    }

    @Override
    public void setCachedBalance(Context app, BigDecimal balance) {
        BRSharedPrefs.putCachedBalance(app, getIso(app), balance);
        for (OnBalanceChangedListener listener : balanceListeners) {
            if (listener != null) listener.onBalanceChanged(getIso(app), balance);
        }
    }

    @Override
    public BigDecimal getMaxAmount(Context app) {
        return mWalletEthManager.getMaxAmount(app);
    }

    @Override
    public WalletUiConfiguration getUiConfiguration() {
        return uiConfig;
    }

    @Override
    public WalletSettingsConfiguration getSettingsConfiguration() {
        //no settings for tokens, so empty
        return new WalletSettingsConfiguration();
    }

    @Override
    public BigDecimal getFiatExchangeRate(Context app) {
        BigDecimal fiatData = getFiatForToken(app, new BigDecimal(1), BRSharedPrefs.getPreferredFiatIso(app));
        if (fiatData == null) return null;
        return fiatData; //dollars
    }

    @Override
    public BigDecimal getFiatBalance(Context app) {
        if (app == null) return null;
        return getFiatForSmallestCrypto(app, getCachedBalance(app), null);
    }

    @Override
    public BigDecimal getFiatForSmallestCrypto(Context app, BigDecimal amount, CurrencyEntity ent) {
        if (amount == null || amount.compareTo(new BigDecimal(0)) == 0) return amount;
        String iso = BRSharedPrefs.getPreferredFiatIso(app);
        if (ent != null) {
            //passed in a custom CurrencyEntity
            //get crypto amount
            //multiply by fiat rate
            return amount.multiply(new BigDecimal(ent.rate));
        }

        BigDecimal fiatData = getFiatForToken(app, amount, iso);
        if (fiatData == null) return null;
        return fiatData;
    }

    @Override
    public BigDecimal getCryptoForFiat(Context app, BigDecimal fiatAmount) {
        if (fiatAmount == null || fiatAmount.compareTo(new BigDecimal(0)) == 0) return fiatAmount;
        String iso = BRSharedPrefs.getPreferredFiatIso(app);
        return getTokensForFiat(app, fiatAmount, iso);
    }

    @Override
    public BigDecimal getCryptoForSmallestCrypto(Context app, BigDecimal amount) {
        if (amount == null) return null;
        return amount; //only using Tokens
    }

//    private boolean isWei(BigDecimal amount) {
//        amount = amount.stripTrailingZeros();
//        //if the maount has more than 18 digits, then it's probably WEI (ETH fee amount)
//        //Use ETH wallet to convert
//        return amount.precision() - amount.scale() >= 10;
//    }

    @Override
    public BigDecimal getSmallestCryptoForCrypto(Context app, BigDecimal amount) {
        return amount; //only using Tokens
    }

    @Override
    public BigDecimal getSmallestCryptoForFiat(Context app, BigDecimal amount) {
        return getCryptoForFiat(app, amount);
    }

    //pass in a token amount and return the specified amount in fiat
    //erc20 rates are in BTC (thus this math)
    private BigDecimal getFiatForToken(Context app, BigDecimal tokenAmount, String code) {
        //fiat rate for btc
        CurrencyEntity btcRate = CurrencyDataSource.getInstance(app).getCurrencyByCode(app, "BTC", code);

        //Btc rate for the token
        CurrencyEntity tokenBtcRate = CurrencyDataSource.getInstance(app).getCurrencyByCode(app, getIso(app), "BTC");

        if (btcRate == null) {
            Log.e(TAG, "getUsdFromBtc: No USD rates for BTC");
            return null;
        }
        if (tokenBtcRate == null) {
            Log.e(TAG, "getUsdFromBtc: No BTC rates for ETH");
            return null;
        }
        if (tokenBtcRate.rate == 0 || btcRate.rate == 0) return new BigDecimal(0);


        return tokenAmount.multiply(new BigDecimal(tokenBtcRate.rate)).multiply(new BigDecimal(btcRate.rate));
    }

    //pass in a fiat amount and return the specified amount in tokens
    //Token rates are in BTC (thus this math)
    private BigDecimal getTokensForFiat(Context app, BigDecimal fiatAmount, String code) {
        //fiat rate for btc
        CurrencyEntity btcRate = CurrencyDataSource.getInstance(app).getCurrencyByCode(app, "BTC", code);
        //Btc rate for token
        CurrencyEntity tokenBtcRate = CurrencyDataSource.getInstance(app).getCurrencyByCode(app, getIso(app), "BTC");
        if (btcRate == null) {
            Log.e(TAG, "getUsdFromBtc: No USD rates for BTC");
            return null;
        }
        if (tokenBtcRate == null) {
            Log.e(TAG, "getUsdFromBtc: No BTC rates for ETH");
            return null;
        }

        return fiatAmount.divide(new BigDecimal(tokenBtcRate.rate).multiply(new BigDecimal(btcRate.rate)), 8, BRConstants.ROUNDING_MODE);
    }

    public static void printTxInfo(BREthereumTransaction tx) {
        StringBuilder builder = new StringBuilder();
        builder.append("|Tx:");
        builder.append("|Amount:");
        builder.append(tx.getAmount());
        builder.append("|Fee:");
        builder.append(tx.getFee());
        builder.append("|Source:");
        builder.append(tx.getSourceAddress());
        builder.append("|Target:");
        builder.append(tx.getTargetAddress());
        Log.e(TAG, "printTxInfo: " + builder.toString());
    }
}
