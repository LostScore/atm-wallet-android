package cash.just.wac

import cash.just.wac.model.AtmListResponse
import cash.just.wac.model.CashCodeResponse
import cash.just.wac.model.CashCodeStatusResponse
import cash.just.wac.model.SendVerificationCodeResponse
import retrofit2.Call

interface Wac {

    enum class BtcSERVER {
        MAIN_NET,
        TEST_NET
    }

    interface SessionCallback {
        fun onSessionCreated(sessionKey:String)
        fun onError(errorMessage:String?)
    }

    fun createSession(server:BtcSERVER, listener:SessionCallback)
    fun isSessionCreated(): Boolean
    fun getAtmList(): Call<AtmListResponse>
    fun getAtmListByLocation(latitude:String, longitude:String): Call<AtmListResponse>
    fun checkCashCodeStatus(code:String): Call<CashCodeStatusResponse>
    fun createCashCode(atmId:String, amount:String, verificationCode:String): Call<CashCodeResponse>
    fun sendVerificationCode(firstName:String, lastName:String, phoneNumber:String?, email:String?): Call<SendVerificationCodeResponse>
}