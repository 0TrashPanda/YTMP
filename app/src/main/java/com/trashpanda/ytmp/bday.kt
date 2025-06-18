package com.trashpanda.ytmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trashpanda.ytmp.ui.theme.YTMPTheme

//class MainActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        setContent {
//            YTMPTheme {
//                Surface (
//                    modifier = Modifier.fillMaxSize(),
//                    color = MaterialTheme.colorScheme.background
//                ) {
//                    GreetingText(
//                        message = "Happy Birthday Sam!",
//                        from = "From Emma",
//                        modifier = Modifier.padding(8.dp)
//                    )
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun GreetingText(message: String, from: String, modifier: Modifier = Modifier) {
//    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
//        Text(
//            text = "Hello $message!",
//            fontSize = 100.sp,
//            lineHeight = 116.sp,
//            textAlign = TextAlign.Center,
//        )
//        Text(
//            text = from,
//            fontSize = 36.sp,
//            modifier = Modifier
//                .padding(16.dp)
//                .align(alignment = Alignment.End),
//        )
//    }
//}
//
//@Preview(showBackground = true)
//@Composable
//fun BirthdayCardPreview() {
//    YTMPTheme {
//        GreetingText(message = "Happy Birthday Sam!", from = "From Emma")
//    }
//}
