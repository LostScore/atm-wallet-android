/**
 * BreadWallet
 *
 *
 * Created by Ahsan Butt <ahsan.butt@breadwallet.com> 8/1/19.
 * Copyright (c) 2019 breadwallet LLC
 *
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.breadwallet.ui.home

import android.content.Context
import com.breadwallet.BreadApp
import com.breadwallet.crypto.Amount
import com.breadwallet.crypto.Wallet as CryptoWallet
import com.breadwallet.model.Experiments
import com.breadwallet.repository.ExperimentsRepositoryImpl
import com.breadwallet.repository.RatesRepository
import com.breadwallet.repository.MessagesRepository
import com.breadwallet.tools.manager.BRSharedPrefs
import com.breadwallet.tools.manager.PromptManager
import com.breadwallet.tools.sqlite.RatesDataSource
import com.breadwallet.tools.util.BRConstants
import com.breadwallet.tools.util.CurrencyUtils
import com.breadwallet.tools.util.EventUtils
import com.breadwallet.ui.util.bindConsumerIn
import com.spotify.mobius.Connection
import com.spotify.mobius.functions.Consumer
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import java.lang.Exception
import java.math.BigDecimal

@UseExperimental(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeScreenEffectHandler(
    private val output: Consumer<HomeScreenEvent>,
    private val context: Context
) : Connection<HomeScreenEffect>,
    CoroutineScope,
    RatesDataSource.OnDataChanged {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    init {
        RatesDataSource.getInstance(context).addOnDataChangedListener(this)
    }

    override fun accept(value: HomeScreenEffect) {
        when (value) {
            HomeScreenEffect.LoadWallets -> loadWallets()
            HomeScreenEffect.LoadPrompt -> loadPrompt()
            HomeScreenEffect.LoadIsBuyBellNeeded -> loadIsBuyBellNeeded()
            HomeScreenEffect.CheckInAppNotification -> checkInAppNotification()
            HomeScreenEffect.CheckIfShowBuyAndSell -> checkIfShowBuyAndSell()
            is HomeScreenEffect.RecordPushNotificationOpened -> recordPushNotificationOpened(value.campaignId)
        }
    }

    override fun dispose() {
        coroutineContext.cancelChildren()

        RatesDataSource.getInstance(context).removeOnDataChangedListener(this)
    }

    private fun loadWallets() {
        val breadBox = BreadApp.getBreadBox()

        // Update wallets list
        breadBox.wallets()
            .mapLatest { wallets -> wallets.map { it.asWallet() } }
            .map { HomeScreenEvent.OnWalletsAdded(it) }
            .bindConsumerIn(output, this)

        // Update wallet balances
        breadBox.currencyCodes()
            .flatMapLatest { it.asFlow() }
            .flatMapMerge { currencyCode ->
                breadBox.wallet(currencyCode)
                    .distinctUntilChangedBy { it.balance }
            }
            .map {
                HomeScreenEvent.OnWalletBalanceUpdated(
                    currencyCode = it.currency.code,
                    balance = getBalance(it.balance),
                    fiatBalance = getBalanceInFiat(it.balance),
                    fiatPricePerUnit = getFiatPerPriceUnit(it.currency.code),
                    priceChange = getPriceChange(it.currency.code)
                )
            }
            .bindConsumerIn(output, this)

        // Update wallet sync state
        breadBox.currencyCodes()
            .flatMapLatest { it.asFlow() }
            .flatMapMerge {
                breadBox.walletSyncState(it)
                    .mapLatest { syncState ->
                        HomeScreenEvent.OnWalletSyncProgressUpdated(
                            currencyCode = syncState.currencyCode,
                            progress = syncState.percentComplete,
                            syncThroughMillis = syncState.timestamp,
                            isSyncing = syncState.isSyncing
                        )
                    }
            }
            .bindConsumerIn(output, this)
    }

    private fun loadPrompt() {
        // TODO: refactor to its own generic effect handler
        val promptId = PromptManager.nextPrompt(context)
        if (promptId != null) {
            EventUtils.pushEvent(EventUtils.EVENT_PROMPT_PREFIX +
                    PromptManager.getPromptName(promptId) + EventUtils.EVENT_PROMPT_SUFFIX_DISPLAYED)
        }
        output.accept(HomeScreenEvent.OnPromptLoaded(promptId))
    }

    private fun loadIsBuyBellNeeded() {
        val isBuyBellNeeded = ExperimentsRepositoryImpl.isExperimentActive(Experiments.BUY_NOTIFICATION) && CurrencyUtils.isBuyNotificationNeeded(context)
        output.accept(HomeScreenEvent.OnBuyBellNeededLoaded(isBuyBellNeeded))
    }

    private fun checkInAppNotification() {
        val notification = MessagesRepository.getInAppNotification(context) ?: return

        // If the notification contains an image we need to pre fetch it to avoid showing the image space empty
        // while we fetch the image while the notification is shown.
        when (notification.imageUrl == null) {
            true -> output.accept(HomeScreenEvent.OnInAppNotificationProvided(notification))
            false -> {
                Picasso.get().load(notification.imageUrl).fetch(object : Callback {

                    override fun onSuccess() {
                        output.accept(HomeScreenEvent.OnInAppNotificationProvided(notification))
                    }

                    override fun onError(exception: Exception) {
                    }
                })
            }
        }
    }

    private fun recordPushNotificationOpened(campaignId: String) {
        val attributes = HashMap<String, String>()
        attributes[EventUtils.EVENT_ATTRIBUTE_CAMPAIGN_ID] = campaignId
        EventUtils.pushEvent(EventUtils.EVENT_MIXPANEL_APP_OPEN, attributes)
        EventUtils.pushEvent(EventUtils.EVENT_PUSH_NOTIFICATION_OPEN)
    }

    override fun onChanged() {
        val breadBox = BreadApp.getBreadBox()

        breadBox.currencyCodes()
            .take(1)
            .flatMapLatest { it.asFlow() }
            .flatMapConcat { breadBox.wallet(it).take(1) }
            .onEach { updateBalance(it.currency.code, it.balance) }
            .launchIn(this)
    }

    private fun getFiatPerPriceUnit(currencyCode: String): BigDecimal {
        return RatesRepository.getInstance(context).getFiatForCrypto(BigDecimal.ONE, currencyCode, BRSharedPrefs.getPreferredFiatIso(context))
    }

    private fun updateBalance(currencyCode: String, balanceAmt: Amount) {
        val balance = getBalance(balanceAmt)
        val balanceInFiat = getBalanceInFiat(balanceAmt)
        val fiatPricePerUnit = getFiatPerPriceUnit(currencyCode)
        val priceChange = getPriceChange(currencyCode)

        output.accept(HomeScreenEvent.OnWalletBalanceUpdated(currencyCode, balance, balanceInFiat, fiatPricePerUnit, priceChange))
    }

    private fun getBalanceAmtInBase(balance: Amount): BigDecimal {
        return balance.doubleAmount(balance.unit.base).or(0.0).toBigDecimal()
    }

    private fun getBalance(balanceAmt: Amount) : BigDecimal {
        return balanceAmt.doubleAmount(balanceAmt.unit).or(0.0).toBigDecimal()
    }

    private fun getBalanceInFiat(balanceAmt: Amount): BigDecimal {
        val balance = getBalance(balanceAmt)
        return RatesRepository.getInstance(context).getFiatForCrypto(balance, balanceAmt.currency.code, BRSharedPrefs.getPreferredFiatIso(context)) ?: BigDecimal.ZERO
    }

    private fun getPriceChange(currencyCode: String) = RatesRepository.getInstance(context).getPriceChange(currencyCode)

    private fun CryptoWallet.asWallet(): Wallet {
        return Wallet(
                currencyName = currency.name,
                currencyCode = currency.code,
                fiatPricePerUnit = getFiatPerPriceUnit(currency.code),
                balance = getBalance(balance),
                fiatBalance = getBalanceInFiat(balance),
                syncProgress = 0f, // will update via sync events
                syncingThroughMillis = 0L, // will update via sync events
                priceChange = getPriceChange(currency.code)
        )
    }

    private fun checkIfShowBuyAndSell() {
        val showBuyAndSell = ExperimentsRepositoryImpl.isExperimentActive(Experiments.BUY_SELL_MENU_BUTTON)
                && BRSharedPrefs.getPreferredFiatIso() == BRConstants.USD
        output.accept(HomeScreenEvent.OnShowBuyAndSell(showBuyAndSell))
    }
}
