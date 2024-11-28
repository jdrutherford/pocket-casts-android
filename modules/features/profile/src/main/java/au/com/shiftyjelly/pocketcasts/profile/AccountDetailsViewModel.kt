package au.com.shiftyjelly.pocketcasts.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.account.viewmodel.NewsletterSource
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.models.to.SignInState
import au.com.shiftyjelly.pocketcasts.models.to.SubscriptionStatus
import au.com.shiftyjelly.pocketcasts.models.type.Subscription
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionMapper
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionTier
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.subscription.ProductDetailsState
import au.com.shiftyjelly.pocketcasts.repositories.subscription.SubscriptionManager
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncManager
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import au.com.shiftyjelly.pocketcasts.utils.Gravatar
import au.com.shiftyjelly.pocketcasts.utils.Optional
import com.automattic.android.tracks.crashlogging.CrashLogging
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Duration as JavaDuration

@HiltViewModel
class AccountDetailsViewModel
@Inject constructor(
    subscriptionManager: SubscriptionManager,
    userManager: UserManager,
    private val settings: Settings,
    private val syncManager: SyncManager,
    private val analyticsTracker: AnalyticsTracker,
    private val crashLogging: CrashLogging,
    private val subscriptionMapper: SubscriptionMapper,
) : ViewModel() {
    internal val deleteAccountState = MutableStateFlow<DeleteAccountState>(DeleteAccountState.Empty)
    internal val signInState = userManager.getSignInState()
    internal val marketingOptIn = settings.marketingOptIn.flow
    private val subscription = subscriptionManager.observeProductDetails().map { state ->
        if (state is ProductDetailsState.Loaded) {
            val subscriptions = state.productDetails
                .mapNotNull {
                    subscriptionMapper.mapFromProductDetails(
                        productDetails = it,
                        isOfferEligible = subscriptionManager.isOfferEligible(
                            SubscriptionTier.fromProductId(it.productId),
                        ),
                    )
                }
            val filteredOffer = Subscription.filterOffers(subscriptions)
            Optional.of(subscriptionManager.getDefaultSubscription(filteredOffer))
        } else {
            Optional.empty()
        }
    }

    internal val headerState = signInState.asFlow().map { state ->
        when (state) {
            is SignInState.SignedOut -> AccountHeaderState.empty()
            is SignInState.SignedIn -> {
                val status = state.subscriptionStatus
                AccountHeaderState(
                    email = state.email,
                    imageUrl = Gravatar.getUrl(state.email),
                    subscription = when (status) {
                        is SubscriptionStatus.Free -> SubscriptionHeaderState.Free
                        is SubscriptionStatus.Paid -> {
                            val activeSubscription = status.subscriptions.getOrNull(status.index)
                            if (activeSubscription == null || activeSubscription.tier in paidTiers) {
                                if (status.autoRenew) {
                                    SubscriptionHeaderState.PaidRenew(
                                        tier = status.tier,
                                        expiresIn = status.expiryDate.toExpiresInDuration(),
                                        frequency = status.frequency,
                                    )
                                } else {
                                    SubscriptionHeaderState.PaidCancel(
                                        tier = status.tier,
                                        expiresIn = status.expiryDate.toExpiresInDuration(),
                                        isChampion = status.isPocketCastsChampion,
                                        platform = status.platform,
                                        giftDaysLeft = status.giftDays,
                                    )
                                }
                            } else if (activeSubscription.autoRenewing) {
                                SubscriptionHeaderState.SupporterRenew(
                                    tier = activeSubscription.tier,
                                    expiresIn = activeSubscription.expiryDate?.toExpiresInDuration(),
                                )
                            } else {
                                SubscriptionHeaderState.SupporterCancel(
                                    tier = activeSubscription.tier,
                                    expiresIn = activeSubscription.expiryDate?.toExpiresInDuration(),
                                )
                            }
                        }
                    },
                )
            }
        }
    }.stateIn(viewModelScope, started = SharingStarted.Eagerly, initialValue = AccountHeaderState.empty())

    internal val showUpgradeBanner = combine(
        signInState.asFlow(),
        subscription.asFlow(),
    ) { signInState, subscription ->
        val signedInState = signInState as? SignInState.SignedIn
        val isGiftExpiring = (signedInState?.subscriptionStatus as? SubscriptionStatus.Paid)?.isExpiring == true
        subscription != null && (signInState.isSignedInAsFree || isGiftExpiring)
    }.stateIn(viewModelScope, started = SharingStarted.Eagerly, initialValue = false)

    internal val sectionsState = combine(
        signInState.asFlow(),
        marketingOptIn,
    ) { signInState, marketingOptIn ->
        val signedInState = signInState as? SignInState.SignedIn
        val isGiftExpiring = (signedInState?.subscriptionStatus as? SubscriptionStatus.Paid)?.isExpiring == true
        AccountSectionsState(
            isSubscribedToNewsLetter = marketingOptIn,
            email = signedInState?.email,
            canChangeCredentials = !syncManager.isGoogleLogin(),
            canUpgradeAccount = signedInState?.isSignedInAsPlus == true && isGiftExpiring,
            canCancelSubscription = signedInState?.isSignedInAsPaid == true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = AccountSectionsState(
            isSubscribedToNewsLetter = false,
            email = null,
            canChangeCredentials = false,
            canUpgradeAccount = false,
            canCancelSubscription = false,
        ),
    )

    internal val automotiveSectionsState = combine(
        signInState.asFlow(),
        marketingOptIn,
    ) { signInState, marketingOptIn ->
        AccountSectionsState(
            isSubscribedToNewsLetter = marketingOptIn,
            email = (signInState as? SignInState.SignedIn)?.email,
            availableSections = listOf(AccountSection.SignOut),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = AccountSectionsState(
            isSubscribedToNewsLetter = false,
            email = null,
            availableSections = listOf(AccountSection.SignOut),
        ),
    )

    fun deleteAccount() {
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { syncManager.deleteAccount().await() }
                val success = response.success ?: false
                deleteAccountState.value = if (success) {
                    DeleteAccountState.Success("OK")
                } else {
                    DeleteAccountState.Failure(response.message)
                }
            } catch (e: Throwable) {
                deleteAccountError(e)
            }
        }
    }

    private fun deleteAccountError(throwable: Throwable) {
        deleteAccountState.value = DeleteAccountState.Failure(message = null)
        Timber.e(throwable)
        crashLogging.sendReport(throwable, message = "Delete account failed")
    }

    fun clearDeleteAccountState() {
        deleteAccountState.value = DeleteAccountState.Empty
    }

    fun updateNewsletter(isChecked: Boolean) {
        analyticsTracker.track(
            AnalyticsEvent.NEWSLETTER_OPT_IN_CHANGED,
            mapOf(SOURCE_KEY to NewsletterSource.PROFILE.analyticsValue, ENABLED_KEY to isChecked),
        )
        settings.marketingOptIn.set(isChecked, updateModifiedAt = true)
    }

    private fun Date.toExpiresInDuration(): Duration {
        return JavaDuration.between(Instant.now(), toInstant())
            .toKotlinDuration()
            .coerceAtLeast(Duration.ZERO)
    }

    companion object {
        private const val SOURCE_KEY = "source"
        private const val ENABLED_KEY = "enabled"

        private val paidTiers = listOf(SubscriptionTier.PLUS, SubscriptionTier.PATRON)
    }
}

sealed class DeleteAccountState {
    object Empty : DeleteAccountState()
    data class Success(val result: String) : DeleteAccountState()
    data class Failure(val message: String?) : DeleteAccountState()
}
