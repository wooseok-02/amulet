package com.example.bukku

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

@Composable
internal fun TargetSelector(model: AppModel) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow("WHO IS THIS FORTUNE FOR?")
        Row {
            listOf(false to "나의 부적", true to "친구에게 선물").forEach { (friend, label) ->
                val selected = friend == model.friendMode
                Row(Modifier.weight(1f).background(if (selected) Yellow else Color.White).border(.7.dp, Brown)
                    .drawBehind { if (selected) drawRect(Red, Offset(0f, size.height-3.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
                    .clickable(enabled = !model.busy, role = Role.Tab) { model.friendMode = friend }.padding(horizontal = 10.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (friend) Icons.Outlined.CardGiftcard else Icons.Outlined.BookmarkBorder, null, Modifier.size(20.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(if (friend) "친구의 정보로 만들기" else "저장된 내 정보로 보기", fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeatureHero(eyebrow: String, title: String, description: String, accent: String = "") {
    Column(Modifier.fillMaxWidth().dots().border(.7.dp, Brown).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Eyebrow(eyebrow)
        Text(title, fontFamily = Serif, fontSize = 31.sp, lineHeight = 43.sp)
        if (accent.isNotBlank()) Text(accent, fontFamily = Serif, fontWeight = FontWeight.Bold, color = Red, fontSize = 31.sp, lineHeight = 43.sp)
        Text(description, fontSize = 12.sp, lineHeight = 22.sp)
    }
}

@Composable
internal fun ProfileScreen(model: AppModel) {
    val profile = model.profile
    FeatureHero("MY FORTUNE PROFILE", "내 정보", "나의 운세와 부적에 사용되는 기본 정보를 확인할 수 있어요.")
    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Eyebrow("오늘도 좋은 흐름과 함께해요")
        val name = profile.optString("name").ifBlank { "행운" }
        Text(if (name.endsWith("님")) name else "${name}님", fontFamily = Serif, fontSize = 30.sp)
        Text(if (model.preview) "preview@example.com" else model.email, fontSize = 13.sp)
        Column(Modifier.fillMaxWidth().background(Color.White).border(.7.dp, Brown)) {
            val lunar = profile.optString("calendar_type") == "LUNAR"
            listOf(
                "생년월일" to profile.optString("birth_date").ifBlank { "미등록" },
                "달력 기준" to if (lunar) "음력 · ${if (profile.optBoolean("is_leap_month")) "윤달" else "평달"}" else "양력",
                "태어난 시간" to profile.optString("birth_time").takeIf { it.isNotBlank() && it != "null" }?.take(5).orEmpty().ifBlank { "모름" },
                "성별" to when (profile.optString("gender")) { "MALE" -> "남성"; "FEMALE" -> "여성"; else -> "미등록" },
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontSize = 12.sp); Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(color = Brown.copy(alpha = .2f))
            }
        }
        Text("이 정보로 운세를 살펴보고 있어요.\n생년월일과 태어난 시간을 바탕으로 오늘의 흐름을 안내합니다.", fontSize = 12.sp, lineHeight = 22.sp)
        Action(if (model.preview) "미리보기 종료" else "로그아웃", model.busy) { model.logout() }
    }
}

@Composable
internal fun CategoryPicker(label: String, selected: String, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box {
            Row(Modifier.fillMaxWidth().background(Color.White).border(.7.dp, Brown).clickable(enabled = enabled) { open = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected, Modifier.weight(1f), fontSize = 13.sp); Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(open, { open = false }) { listOf("전체", "연애", "금전", "취업", "학업", "건강", "인간관계").forEach { item ->
                DropdownMenuItem(text = { Text(item) }, onClick = { choose(item); open = false })
            } }
        }
    }
}

@Composable
internal fun FriendFlow(model: AppModel) {
    val context = LocalContext.current
    val form = model.friendForm
    val scroll = rememberScrollState()
    LaunchedEffect(model.friendFortune != null) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        TargetSelector(model)
        FeatureHero("A FORTUNE FOR SOMEONE SPECIAL", "친구의 오늘에", "친구의 생년월일과 태어난 시간을 입력하면 그날의 흐름에 맞는 응원 부적을 만들 수 있어요.", "행운을 선물해요.")
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (model.friendFortune == null) {
                Eyebrow("01 · FRIEND'S INFORMATION")
                Text("누구에게 행운을 전할까요?", fontFamily = Serif, fontSize = 25.sp)
                Field("친구 이름 또는 별명", form.name, "친구 이름 또는 별명", model.friendErrors["name"], enabled = !model.busy) { model.friendForm = form.copy(name = it) }
                Picker("생년월일", form.birth, "연도-월-일", Icons.Outlined.CalendarToday, model.friendErrors["birth"], !model.busy) {
                    val initial = runCatching { LocalDate.parse(form.birth) }.getOrDefault(LocalDate.of(2000,1,1))
                    DatePickerDialog(context, { _, y, m, d -> model.friendForm = model.friendForm.copy(birth = LocalDate.of(y,m+1,d).toString()) }, initial.year, initial.monthValue-1, initial.dayOfMonth).apply {
                        datePicker.minDate = LocalDate.of(1900,1,1).atStartOfDay(seoul).toInstant().toEpochMilli()
                        datePicker.maxDate = LocalDate.of(LocalDate.now(seoul).year,12,31).atStartOfDay(seoul).toInstant().toEpochMilli()
                    }.show()
                }
                Choices("달력 기준", listOf("양력","음력"), if (form.lunar) 1 else 0, !model.busy) { model.friendForm = form.copy(lunar = it==1, leap = null) }
                if (form.lunar) Choices("윤달 여부", listOf("평달","윤달"), when(form.leap) { false -> 0; true -> 1; null -> -1 }, !model.busy, model.friendErrors["leap"]) { model.friendForm = form.copy(leap = it==1) }
                Picker("태어난 시간", form.time, "시간 모름", Icons.Outlined.Schedule, null, !model.busy) {
                    TimePickerDialog(context, { _, h, m -> model.friendForm = model.friendForm.copy(time = String.format(Locale.ROOT,"%02d:%02d",h,m)) },12,0,true).show()
                }
                TextButton(onClick = { model.friendForm = form.copy(time = "") }, enabled = !model.busy) { Text("시간 모름으로 입력") }
                var relationMenu by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("친구와의 관계", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box {
                        OutlinedButton(onClick = { relationMenu = true }, enabled = !model.busy, shape = RectangleShape, border = BorderStroke(.7.dp,Brown), modifier = Modifier.fillMaxWidth()) {
                            Text(form.relationship.ifBlank { "관계를 선택해주세요" }, Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore,null)
                        }
                        DropdownMenu(relationMenu,{ relationMenu=false }) { listOf("친구","연인","가족","동료").forEach { relation ->
                            DropdownMenuItem(text={Text(relation)},onClick={model.friendForm=form.copy(relationship=relation);relationMenu=false})
                        } }
                    }
                    model.friendErrors["relationship"]?.let { Text(it,color=Red,fontSize=11.sp) }
                }
                Choices("성별",listOf("여성","남성","선택 안 함"),when(form.gender){"FEMALE"->0;"MALE"->1;else->2},!model.busy) { model.friendForm=form.copy(gender=when(it){0->"FEMALE";1->"MALE";else->""}) }
                Text("입력한 정보는 부적 제작에만 사용해요.\n친구에게는 생년월일이나 태어난 시간이 표시되지 않습니다.",fontSize=11.sp,lineHeight=20.sp)
                Action("친구의 오늘 운세 확인하기",model.busy){model.friendToday()}
            } else {
                TextButton(onClick={model.editFriend()},enabled=!model.busy){Icon(Icons.Outlined.ArrowBack,null,Modifier.size(16.dp));Text("친구 정보 다시 입력")}
                FriendFortuneCard(model.friendFortune!!)
                Eyebrow("A MESSAGE FOR YOUR FRIEND")
                Text("어떤 순간을 응원하고 싶나요?",fontFamily=Serif,fontSize=25.sp,lineHeight=36.sp)
                CategoryPicker("상황 선택",model.friendCategory,!model.busy){model.friendCategory=it;model.friendInterpretationVisible=false}
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("친구의 상황",fontSize=12.sp,fontWeight=FontWeight.Bold)
                    OutlinedTextField(model.friendConcern,{if(it.length<=200){model.friendConcern=it;model.friendInterpretationVisible=false}},Modifier.fillMaxWidth().heightIn(min=130.dp),enabled=!model.busy,shape=RectangleShape,
                        placeholder={Text("중요한 면접을 앞둔 친구에게 자신감을 주고 싶어요.",fontSize=12.sp)},
                        colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Brown,focusedBorderColor=Brown,unfocusedContainerColor=Color.White,focusedContainerColor=Color.White))
                    Text("${model.friendConcern.length} / 200",Modifier.align(Alignment.End),fontSize=10.sp)
                }
                Action(if(model.friendInterpretationVisible)"해석 닫기" else "AI 응원 해석 받기",model.busy){model.interpretFriend()}
                if(model.friendInterpretationVisible) model.friendInterpretation?.let { result ->
                    Column(Modifier.background(Color.White).border(.7.dp,Brown).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                        Eyebrow("AI INTERPRETATION")
                        Text(result.optString("day_message"),fontFamily=Serif,fontSize=22.sp,lineHeight=34.sp)
                        Text(result.optString("action_advice"),fontSize=13.sp,lineHeight=23.sp)
                        Action("이 응원으로 부적 만들기",false){model.openEditor()}
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun FriendFortuneCard(fortune: JSONObject) {
    Column(Modifier.fillMaxWidth().background(Color.White).border(.7.dp,Brown)) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Eyebrow("FRIEND'S FORTUNE");Text(fortune.optString("headline"),fontFamily=Serif,fontSize=23.sp,lineHeight=34.sp)}
            Text(fortune.optInt("total_score").toString(),color=Red,fontFamily=Serif,fontSize=40.sp);Text("/100",fontSize=9.sp)
        }
        HorizontalDivider(color=Brown,thickness=.7.dp)
        Text(fortune.optString("total_comment"),Modifier.padding(18.dp),fontSize=13.sp,lineHeight=23.sp)
        Row(Modifier.fillMaxWidth().background(Color(0xFFFFF0D0))){
            listOf("행운 숫자" to "%02d".format(fortune.optInt("lucky_number")),"행운의 색" to fortune.optString("lucky_color"),"응원 행동" to fortune.optString("support_action")).forEach{(label,value)->
                Column(Modifier.weight(1f).border(.7.dp,Brown).padding(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(label,fontSize=9.sp);Text(value,fontFamily=Serif,fontSize=17.sp,lineHeight=24.sp)}
            }
        }
    }
}

@Composable
internal fun CharmEditor(model: AppModel) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var shareError by remember { mutableStateOf(false) }
    LaunchedEffect(model.editorStep) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        TextButton(onClick={model.editorStep-=1}){Icon(Icons.Outlined.ArrowBack,null,Modifier.size(18.dp));Text(if(model.editorStep==1)"해석 화면으로 돌아가기" else "부적 꾸미기로 돌아가기",fontSize=12.sp)}
        Eyebrow(if(model.editorStep==1)"MAKE YOUR OWN CHARM · 01" else "YOUR LUCKY CHARM · 02")
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(model.editorStep==1)"오늘의 부적 꾸미기" else "오늘의 부적 완성",Modifier.weight(1f),fontFamily=Serif,fontSize=27.sp);Text("${model.editorStep} / 2",fontSize=12.sp)}
        HorizontalDivider(color=Brown,thickness=.7.dp)
        if(model.editorStep==1){
            Eyebrow("TOOLS · 꾸미기")
            Choices("문양",listOf("해","별","물결"),listOf("해","별","물결").indexOf(model.draft.pattern),!model.busy && !model.draftSaved && model.shareUrl.isBlank() && !model.uploadUncertain){model.updateDraft(model.draft.copy(pattern=listOf("해","별","물결")[it]))}
            Choices("테두리",listOf("한 줄","두 줄","점선"),listOf("한 줄","두 줄","점선").indexOf(model.draft.frame),!model.busy && !model.draftSaved && model.shareUrl.isBlank() && !model.uploadUncertain){model.updateDraft(model.draft.copy(frame=listOf("한 줄","두 줄","점선")[it]))}
            Text("PREVIEW · 선택한 재료가 바로 적용돼요",fontSize=10.sp)
        } else {
            Eyebrow("READY FOR YOUR DAY")
            Text(if(model.draft.recipient.isBlank())"당신의 오늘을 위한\n부적이 완성됐어요." else "친구에게 전할\n응원 부적이 완성됐어요.",fontFamily=Serif,fontSize=25.sp,lineHeight=37.sp)
            Text(if(model.draft.recipient.isBlank())"보관함에 저장하고, 응원이 필요한 순간에 꺼내 보세요." else "완성된 부적과 함께 진심 어린 응원을 전해보세요.",fontSize=12.sp,lineHeight=21.sp)
        }
        CharmPreview(model.draft,Modifier.fillMaxWidth(.86f).align(Alignment.CenterHorizontally).aspectRatio(5f/7f))
        if(model.editorStep==1){
            Text(if(model.friendMode)"친구의 운세와 상황에 맞춰 추천한 응원 문구예요." else "AI 해석에서 추천한 문구로 만든 기본 부적이에요.",Modifier.align(Alignment.CenterHorizontally),fontSize=11.sp,lineHeight=20.sp)
            Action("다음 단계로 이동",false){model.editorStep=2}
        } else {
            if(model.draft.recipient.isBlank()) {
                Action(if(model.draftSaved || model.uploadUncertain)"보관함에서 확인" else "보관함에 저장",model.busy){
                    if(model.draftSaved || model.uploadUncertain)model.navigate("library") else model.saveAmulet()
                }
                Text("저장한 부적은 보관함에서 다시 확인할 수 있어요.",fontSize=11.sp)
            } else {
                if(model.shareUrl.isBlank())Action("친구에게 보낼 링크 만들기",model.busy || model.uploadUncertain){model.createGift()}
                else {
                    Action("카카오톡으로 전하기",model.busy || BuildConfig.KAKAO_NATIVE_KEY.isBlank()){
                        GiftSharing.share(context,model.shareUrl){model.notifySharingError()}
                    }
                    OutlinedButton(onClick={
                        val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("부적 선물",model.shareUrl))
                    },modifier=Modifier.fillMaxWidth(),shape=RectangleShape){Text("같은 선물 링크 복사")}
                    if(BuildConfig.KAKAO_NATIVE_KEY.isBlank())Text("카카오톡 공유를 준비 중이에요. 링크는 복사해서 전할 수 있어요.",fontSize=11.sp)
                }
                Text("먼저 받은 한 친구의 보관함에 저장돼요. 보낸 사람의 보관함에는 추가되지 않아요.",fontSize=11.sp,lineHeight=20.sp)
            }
            if(model.uploadUncertain)Text("요청 결과를 확인하지 못해 다시 전송하지 않습니다. 저장한 경우 보관함을 먼저 확인해 주세요.",color=Red,fontSize=12.sp)

        }
    }
}

@Composable
internal fun ArchiveScreen(model: AppModel) {
    val month=model.archiveMonth
    val samples = if(model.preview)exampleCharms(month) else emptyList()
    val items = model.archived + samples.filter { sample -> model.archived.none { it.date==sample.date } }
    val monthly = items.filter{runCatching{YearMonth.from(LocalDate.parse(it.date))==month}.getOrDefault(false)}
    val luckyDays = listOf(7,18,27)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
        FeatureHero("MY LUCKY CHARM ARCHIVE","행운 보관함","날짜를 눌러 그날의 부적과 운세 한마디를 다시 만나보세요.")
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Row(Modifier.fillMaxWidth().border(.7.dp,Brown).background(Color(0xFFFFF0D0)).padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={model.reloadArchive(month.minusMonths(1))}){Icon(Icons.Outlined.ChevronLeft,"이전 달")}
                Text("${month.year}  ·  ${"%02d".format(month.monthValue)} 월",Modifier.weight(1f),fontFamily=Serif,fontSize=23.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick={model.reloadArchive(month.plusMonths(1))}){Icon(Icons.Outlined.ChevronRight,"다음 달")}
            }
            Column(Modifier.fillMaxWidth().border(.7.dp,Brown)){
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFF0D0))){listOf("일","월","화","수","목","금","토").forEachIndexed{index,label->Text(label,Modifier.weight(1f).padding(vertical=10.dp),fontSize=11.sp,color=if(index==0)Red else Brown,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
                val start=month.atDay(1).dayOfWeek.value%7
                val cells=((start+month.lengthOfMonth()+6)/7)*7
                (0 until cells step 7).forEach{weekStart->Row{
                    repeat(7){col->val day=weekStart+col-start+1
                        if(day !in 1..month.lengthOfMonth())Box(Modifier.weight(1f).aspectRatio(.8f).border(.3.dp,Brown.copy(alpha=.2f)))
                        else{
                            val date=month.atDay(day);val hasCharm=items.any{it.date==date.toString()};val lucky=day in luckyDays
                            Box(Modifier.weight(1f).aspectRatio(.8f).background(if(hasCharm)Color(0xFFFFF0D0)else Cream).border(if(hasCharm).9.dp else .3.dp,if(hasCharm)Yellow else Brown.copy(alpha=.2f)).clickable{model.openArchiveDate(date)}.padding(4.dp)){
                                if(lucky)Text("✤",Modifier.align(Alignment.TopStart),color=Color(0xFF719C68),fontSize=13.sp)
                                Text(day.toString(),Modifier.align(Alignment.Center),fontSize=12.sp)
                                if(hasCharm)Text("●",Modifier.align(Alignment.BottomCenter),color=Red,fontSize=6.sp)
                            }
                        }
                    }
                }}
            }
            Text("● 저장된 부적     ✤ 이달의 행운의 날 (예시)",fontSize=10.sp)
            if(model.preview)Text("미리보기에는 예시 보관 기록이 함께 표시됩니다.",fontSize=11.sp)
            if(model.archiveLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(model.archiveError.isNotBlank()) {
                Text(model.archiveError,color=Red,fontSize=12.sp)
                TextButton(onClick={model.reloadArchive()}){Text("보관함 다시 불러오기")}
            }
            if(!model.loggedIn && !model.preview)Action("로그인하고 보관함 확인",model.busy){model.navigate("account")}
            MonthlyFortune(month,monthly)
        }
    }

}

@Composable
private fun MonthlyFortune(month: YearMonth, charms: List<Charm>) {
    // Monthly fortune is a separate, currently unspecified API. Never aggregate charm scores.
    Column(Modifier.fillMaxWidth().background(Color.White).border(.7.dp,Brown).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Eyebrow("AI MONTHLY FORTUNE · 예시 해석")
        Text("${month.monthValue}월 종합 운세",fontFamily=Serif,fontSize=26.sp)
        Row(verticalAlignment=Alignment.CenterVertically){Text("안정과 성장",Modifier.weight(1f),fontFamily=Serif,fontSize=23.sp);Text("82",fontFamily=Serif,fontSize=42.sp,color=Red)}
        Text("월 평균 행운 점수 · 예시",fontSize=11.sp)
        Text("차분히 쌓아온 선택들이 눈에 보이는 결과로 이어지는 달이에요. 빠른 결정보다 한 번 더 확인하는 습관이 좋은 흐름을 오래 지켜줍니다.",fontSize=13.sp,lineHeight=24.sp)
        HorizontalDivider(color=Brown,thickness=.7.dp)
        Text("“서두르지 않아도, 당신의 계절은 정확히 오고 있어요.”",fontFamily=Serif,fontSize=18.sp,lineHeight=29.sp)
        Text("행운일 · 7일, 18일, 27일\n행운 행동 · 먼저 안부 묻기",fontSize=12.sp,lineHeight=23.sp)
    }
}

@Composable
internal fun ArchiveDialog(date: LocalDate, charms: List<Charm>, model: AppModel, close: () -> Unit) {
    var selected by remember(date) { mutableStateOf(0) }
    var flipped by remember(date,selected) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if(flipped)180f else 0f,tween(450),label="부적 뒤집기")
    Dialog(close,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Column(Modifier.fillMaxWidth(.92f).fillMaxHeight(.85f).background(Cream).border(.8.dp,Brown).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){Eyebrow("CHARM OF THE DAY");Text(date.format(DateTimeFormatter.ofPattern("yyyy. MM. dd")),fontFamily=Serif,fontSize=23.sp)};IconButton(onClick=close){Icon(Icons.Outlined.Close,"부적 팝업 닫기")}}
            HorizontalDivider(color=Brown,thickness=.7.dp)
            if(model.dateLoading) { CircularProgressIndicator(); Text("그날의 부적을 불러오고 있어요.") }
            else if(charms.isEmpty() && model.detailError.isNotBlank()) {
                Text(model.detailError,color=Red)
                TextButton(onClick={model.openArchiveDate(date)}){Text("다시 불러오기")}
            }
            else if(charms.isEmpty()){
                Spacer(Modifier.height(40.dp));Icon(Icons.Outlined.BookmarkBorder,null,Modifier.size(40.dp).align(Alignment.CenterHorizontally));Text("저장한 부적이 없어요.",Modifier.align(Alignment.CenterHorizontally),fontFamily=Serif,fontSize=24.sp)
                Text("이날의 기록은 아직 비어 있어요. 오늘의 부적을 만들고 보관함에 저장해보세요.",fontSize=13.sp,lineHeight=24.sp)
                OutlinedButton(onClick=close,shape=RectangleShape,modifier=Modifier.fillMaxWidth()){Text("닫기")}
            } else {
                if(charms.size>1)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){charms.forEachIndexed{index,_->FilterChip(selected=selected==index,onClick={selected=index},label={Text("부적 ${index+1}")})}}
                val item=charms[selected.coerceAtMost(charms.lastIndex)]
                LaunchedEffect(item.id) { if(item.serverStored && model.detail?.id!=item.id)model.openArchiveItem(item.id) }
                val charm=if(item.serverStored && model.detail?.id==item.id)model.detail!! else item
                if(item.serverStored && model.detailLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(item.serverStored && model.detailError.isNotBlank()) {
                    Text(model.detailError,color=Red)
                    TextButton(onClick={model.openArchiveItem(item.id)}){Text("상세 다시 불러오기")}
                }
                if(charm.demo)Text("예시 보관 기록",fontSize=10.sp,color=Red)
                Box(Modifier.fillMaxWidth().aspectRatio(5f/7f).graphicsLayer{rotationY=rotation;cameraDistance=12*density}.clickable(role=Role.Button,onClickLabel=if(flipped)"부적 앞면 보기" else "부적을 뒤집어 그날의 운세 보기"){flipped=!flipped}){
                    if(rotation<=90f) {
                        if(charm.serverStored) {
                            if(charm.imageUrl.isNotBlank())RemoteCharmImage(charm,{model.openArchiveItem(charm.id)},{model.refreshImage(charm.id)})
                            else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(model.detailLoading)"이미지를 불러오고 있어요." else "상세를 다시 불러와 주세요.")}
                        } else CharmPreview(charm,Modifier.fillMaxSize())
                    }
                    else Column(Modifier.fillMaxSize().graphicsLayer{rotationY=180f}.background(Color(0xFFFFF0D0)).border(2.dp,Brown).padding(16.dp).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){
                        Spacer(Modifier.height(6.dp));Eyebrow("TODAY'S MESSAGE");Text("오늘의 운세 한마디",fontFamily=Serif,fontSize=22.sp)
                        Text(charm.message.ifBlank{"나의 속도로 차분히 나아가도 좋은 날이에요."},fontSize=13.sp,lineHeight=23.sp)
                        HorizontalDivider(color=Brown.copy(alpha=.4f))
                        Text("그날의 기록",color=Red,fontSize=11.sp)
                        Text(charm.concern.ifBlank{"저장한 부적과 함께 오늘의 응원을 기억해요."},fontSize=12.sp,lineHeight=22.sp)
                        HorizontalDivider(color=Brown.copy(alpha=.4f))
                        Text(charm.dayMessage.ifBlank{charm.advice},fontSize=13.sp,lineHeight=23.sp)
                        if(charm.actionAdvice.isNotBlank()){Text("행동 조언",color=Red,fontSize=11.sp);Text(charm.actionAdvice,fontSize=13.sp,lineHeight=23.sp)}
                        Text("● 행운의 색 · ${charm.luckyColor}",fontSize=11.sp,color=Color(0xFF719C68))
                    }
                }
                Text("부적을 눌러 앞면과 그날의 상세 운세를 확인해보세요.",fontSize=11.sp,lineHeight=20.sp)
            }
        }
    }
}
