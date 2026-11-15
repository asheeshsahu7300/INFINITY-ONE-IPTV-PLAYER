package com.infinity.one

import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable

/**
 * The INFINITY ONE mark as an image: res/drawable/ic_brand_mark.png.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

/**
 * The full INFINITY ONE logo as an image: res/drawable/ic_brand_logo.png.
 */
@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_logo),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}
