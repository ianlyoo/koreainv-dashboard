package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.insight.*
import com.koreainv.dashboard.ui.theme.LocalDashboardColors
import kotlin.math.*

@Composable internal fun InsightPriceChart(allBars: List<PriceBar>) {
    var months by remember { mutableIntStateOf(3) }
    var candle by remember { mutableStateOf(true) }
    val bars = remember(allBars, months) { insightBars(allBars, months) }
    val selected = remember(bars) { mutableIntStateOf((bars.size - 1).coerceAtLeast(0)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1,3,6,12).forEach { m -> TextButton(onClick = { months=m }, modifier = Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).then(if(months==m) Modifier.liquidGlass(radius=28.dp,role=GlassRole.Control) else Modifier)) { Text(if(m==12) "1Y" else "${m}M") } }
            TextButton(onClick={candle=!candle}, modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription = if(candle) "캔들 차트 표시 중, 라인으로 전환" else "라인 차트 표시 중, 캔들로 전환" }) { Text(if(candle) "라인" else "캔들") }
        }
        if(bars.isEmpty()) Text("제공된 일봉이 없습니다.") else {
            InsightPricePlot(bars,candle,selected)
            InsightSelectedBar(bars,selected)
        }
    }
}
@Composable private fun InsightPricePlot(bars:List<PriceBar>,candle:Boolean,selected:MutableIntState) {
    val colors=LocalDashboardColors.current
    val bounds=remember(bars) { bars.minOf{it.low} to bars.maxOf{it.high} }
    val plotHeight = if (LocalConfiguration.current.screenHeightDp < 760) 176.dp else 216.dp
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(insightPrice(bounds.first) + " USD",style=MaterialTheme.typography.labelMedium); Text(insightPrice(bounds.second) + " USD",style=MaterialTheme.typography.labelMedium) }
        Box(Modifier.fillMaxWidth().height(plotHeight).semantics { contentDescription="일봉 가격과 거래량 차트. 아래 이전·다음 버튼으로 정확한 값을 확인하세요." }
            .pointerInput(bars) { detectTapGestures { selected.intValue=(it.x/size.width*bars.size).toInt().coerceIn(bars.indices) } }
            .pointerInput(bars) { detectHorizontalDragGestures { change,_ -> selected.intValue=(change.position.x/size.width*bars.size).toInt().coerceIn(bars.indices); change.consume() } }
            .drawWithCache {
                val span=(bounds.second-bounds.first).takeIf{it>0} ?: 1.0
                val step=size.width/bars.size
                fun x(i:Int)=(i+.5f)*step
                fun y(v:Double)=((bounds.second-v)/span*size.height*.72).toFloat()
                val path=Path().apply { bars.forEachIndexed { i,b -> if(i==0) moveTo(x(i),y(b.close)) else lineTo(x(i),y(b.close)) } }
                val maxVolume=bars.maxOf{it.volume}.coerceAtLeast(1.0)
                onDrawBehind {
                    for(i in 0..3) { val yy=i*size.height*.24f; drawLine(colors.surfaceBorder,Offset(0f,yy),Offset(size.width,yy),1f) }
                    if(!candle) drawPath(path,colors.success,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    bars.forEachIndexed { i,b ->
                        val color=if(b.close>=b.open) colors.success else colors.error
                        if(candle) { drawLine(color,Offset(x(i),y(b.high)),Offset(x(i),y(b.low)),1f); drawRect(color,Offset(x(i)-step*.3f,min(y(b.open),y(b.close))),Size(max(1f,step*.6f),max(1f,abs(y(b.open)-y(b.close))))) }
                        val h=(b.volume/maxVolume*size.height*.20).toFloat()
                        drawRect(color.copy(alpha=.55f),Offset(x(i)-step*.3f,size.height-h),Size(max(1f,step*.6f),h))
                    }
                    val i=selected.intValue.coerceIn(bars.indices)
                    drawLine(colors.textSecondary,Offset(x(i),0f),Offset(x(i),size.height),1f)
                    drawCircle(colors.success,4.dp.toPx(),Offset(x(i),y(bars[i].close)))
                }
            })
        Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(bars.first().date, style=MaterialTheme.typography.labelSmall, color=colors.textSecondary)
            Text(bars.last().date, style=MaterialTheme.typography.labelSmall, color=colors.textSecondary)
        }
    }
}
@Composable private fun InsightSelectedBar(bars:List<PriceBar>,selected:MutableIntState) {
    val i=selected.intValue.coerceIn(bars.indices); val b=bars[i]
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(b.date,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            TextButton(onClick={selected.intValue=i-1},enabled=i>0,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) { Text("이전") }
            TextButton(onClick={selected.intValue=i+1},enabled=i<bars.lastIndex,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) { Text("다음") }
        }
        val values=listOf("시가" to b.open,"고가" to b.high,"저가" to b.low,"종가" to b.close)
        values.chunked(if (LocalDensity.current.fontScale > 1.2f) 2 else 4).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEach { (label, value) ->
                    Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$label ${insightNumber(value)} USD" }) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Text(insightPrice(value), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        ResponsiveDetailRow("거래량 (주)", insightVolume(b.volume), modifier = Modifier.clearAndSetSemantics { contentDescription = "거래량 ${insightNumber(b.volume)} 주" })
    }
}
@Composable internal fun InsightBarsChart(values:List<Pair<String,Double?>>,baseline:Double?=null) {
    val colors=LocalDashboardColors.current
    val maxValue=remember(values,baseline) { max(values.mapNotNull{it.second}.maxOrNull() ?: 0.0,baseline ?: 0.0).coerceAtLeast(1.0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        values.forEach { (label,value) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ResponsiveDetailRow(label,insightNumber(value,if(baseline!=null) "%" else ""))
                Canvas(Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics{}) {
                    value?.let { drawRect(colors.success,size=Size((size.width*it/maxValue).toFloat().coerceIn(0f,size.width),size.height*.6f)) }
                    baseline?.let { val x=(size.width*it/maxValue).toFloat(); drawLine(colors.textSecondary,Offset(x,0f),Offset(x,size.height),2f) }
                }
            }
        }
    }
}
@Composable internal fun InsightShareChart(label:String,share:InsightShare?) {
    val colors=LocalDashboardColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("콜 ${insightNumber(share?.call,"%")}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Text("풋 ${insightNumber(share?.put,"%")}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        val call=share?.call; val put=share?.put
        if(call!=null && put!=null && call>=0 && put>=0 && call+put>0) Canvas(Modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics{}) {
            val width=(size.width*call/(call+put)).toFloat()
            drawRect(colors.success,size=Size(width,size.height)); drawRect(colors.error,Offset(width,0f),Size(size.width-width,size.height))
        }
    }
}
@Composable internal fun InsightTargetChart(target:InsightTarget?,price:Double?) {
    val colors=LocalDashboardColors.current
    val points=listOfNotNull(target?.low,target?.high,target?.mean,price)
    if(points.size<2) return
    val low=points.min();val high=points.max();val span=(high-low).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription="목표가 범위와 현재가. 정확한 값은 다음 행에 표시됩니다." }) {
        fun x(v:Double)=((v-low)/span*(size.width-16.dp.toPx())+8.dp.toPx()).toFloat()
        drawLine(colors.success.copy(alpha = .45f),Offset(x(target?.low ?: low),size.height/2),Offset(x(target?.high ?: high),size.height/2),6.dp.toPx())
        target?.mean?.let{drawCircle(colors.success,6.dp.toPx(),Offset(x(it),size.height/2))}
        price?.let{drawLine(colors.textPrimary,Offset(x(it),0f),Offset(x(it),size.height),3.dp.toPx())}
    }
}

@Composable internal fun InsightRevenueChart(revenue: InsightRevenue) {
    val colors = LocalDashboardColors.current
    val quarters = revenue.quarters
    if (quarters.isEmpty()) { Text("제공된 분기 매출이 없습니다."); return }
    var selected by remember(revenue) { mutableIntStateOf(0) }
    val maxRevenue = remember(revenue) { quarters.mapNotNull { it.revenue }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0 }
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {
            contentDescription = "회계 분기별 매출 막대 차트. 아래 분기 버튼과 전체 정확한 값에서 확인하세요."
        }.pointerInput(revenue) {
            detectTapGestures { selected = (it.x / size.width * quarters.size).toInt().coerceIn(quarters.indices) }
        }) {
            val step = size.width / quarters.size
            quarters.forEachIndexed { i, q ->
                q.revenue?.let { value ->
                    val height = (value / maxRevenue * size.height).toFloat().coerceIn(0f, size.height)
                    drawRect(if (i == selected) colors.success else colors.success.copy(alpha = .45f),
                        Offset((i + .15f) * step, size.height - height), Size(step * .7f, height))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            quarters.forEachIndexed { i,q -> TextButton(onClick = { selected = i }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text(insightText(q.label)) } }
        }
        Spacer(Modifier.height(8.dp))
        val quarter = quarters[selected.coerceIn(quarters.indices)]
        InsightRows(listOf("선택 분기" to insightText(quarter.label), "매출" to insightNumber(quarter.revenue, " USD"), "전년 대비" to insightQuarterChange(quarter)))
    }
}
