package me.pluralware.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import me.pluralware.mobile.ui.TokenEntryScreen
import me.pluralware.mobile.ui.TokenEntryViewModel
import me.pluralware.shared.repository.EncryptedTokenStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tokenStore = EncryptedTokenStore.get(applicationContext)
        setContent {
            MaterialTheme {
                val vm: TokenEntryViewModel = viewModel(
                    factory = TokenEntryViewModel.Factory(tokenStore, applicationContext),
                )
                TokenEntryScreen(viewModel = vm)
            }
        }
    }
}
