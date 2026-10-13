package com.example.bukku

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

private val Cream = Color(0xFFFFF8E7)
private val Brown = Color(0xFF50382B)
private val Yellow = Color(0xFFF9CB49)
private val Red = Color(0xFFD8472E)
private val Green = Color(0xFFA4D65B)
private val Serif = FontFamily(Font(R.font.noto_serif_kr))

@Composable
internal fun BukkuUi(model: AppModel) {
    var settings by remember { mutableStateOf(false) }
    var serverInput by remember { mutableStateOf(model.server) }
    var notice by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = lightColorScheme(primary = Brown, background = Cream, surface = Color.White, onSurface = Brown, onBackground = Brown, error = Red)) {
        Scaffold(containerColor = Cream, modifier = Modifier.fillMaxSize().imePadding(),
            topBar = { Header { notice = "새로운 알림이 없습니다.\n알림 API 연결 전 예시 화면입니다." } },
            bottomBar = { Navigation(model.page) { if (!model.busy) model.page = it } }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (model.preview) Row(Modifier.fillMaxWidth().background(Color(0xFFFFEFD0)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("디자인 미리보기 · 예시 데이터", Modifier.weight(1f), fontSize = 11.sp)
                    Text("종료", Modifier.clickable { model.logout() }, fontSize = 12.sp)
                }
                if (model.message.isNotBlank()) Text(model.message, Modifier.fillMaxWidth().background(Color(0xFFFFEAE1)).padding(14.dp), color = Red, fontSize = 13.sp)
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
                when {
                    model.restoring -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("로그인 정보를 확인하고 있어요.")
                    }
                    model.restoreFailed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("자동 로그인에 연결이 필요해요", fontFamily = Serif, fontSize = 24.sp)
                        Text("저장된 로그인 정보는 유지됩니다. 인터넷 연결과 서버 주소를 확인하고 다시 시도해 주세요.")
                        Action("다시 시도", false) { model.restore() }
                        TextButton(onClick = { serverInput = model.server; settings = true }) { Text("서버 설정") }
                        TextButton(onClick = { model.logout() }) { Text("로그인 정보 지우기") }
                    }
                    model.page == "account" -> Account(model) { serverInput = model.server; settings = true }
                    model.page == "library" -> Library()
                    else -> Home(model) { notice = it }
                }
            }
        }
        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("서버 설정") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("백엔드 팀에서 받은 HTTPS 서버 주소를 입력해 주세요. 서버를 변경하면 저장된 로그인 정보가 삭제됩니다.")
                OutlinedTextField(serverInput, { serverInput = it }, singleLine = true, placeholder = { Text("https://your-server.onrender.com") })
            } }, confirmButton = { TextButton(onClick = { if (model.saveServer(serverInput)) settings = false }) { Text("저장") } }, dismissButton = { TextButton(onClick = { settings = false }) { Text("취소") } })
        if (notice.isNotBlank()) AlertDialog(onDismissRequest = { notice = "" }, title = { Text("안내") }, text = { Text(notice) }, confirmButton = { TextButton(onClick = { notice = "" }) { Text("확인") } })
    }
}

@Composable
private fun Header(notice: () -> Unit) {
    val source = ImageBitmap.imageResource(R.drawable.design_reference)
    Row(Modifier.fillMaxWidth().background(Cream).border(.7.dp, Brown).statusBarsPadding().padding(horizontal = 17.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(38.dp, 43.dp)) {
            drawImage(source, srcOffset = IntOffset(32, 18), srcSize = IntSize(55, 60), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("불안을 행운으로", fontFamily = Serif, fontSize = 17.sp)
            Text("FORTUNE FOR TODAY", fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedIconButton(onClick = notice, modifier = Modifier.size(38.dp), border = BorderStroke(.7.dp, Brown)) { Icon(Icons.Outlined.NotificationsNone, "알림", Modifier.size(20.dp)) }
    }
}

@Composable
private fun Navigation(page: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Cream).navigationBarsPadding().height(64.dp)) {
        listOf(Triple("home", "홈", Icons.Outlined.Home), Triple("library", "보관함", Icons.Outlined.Inventory2), Triple("account", "내 정보", Icons.Outlined.PersonOutline)).forEach { (id, label, icon) ->
            Column(Modifier.weight(1f).fillMaxHeight().background(if (page == id) Yellow else Cream).border(.7.dp, Brown)
                .drawBehind { if (page == id) drawRect(Red, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
                .clickable { select(id) }.padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, label, Modifier.size(23.dp)); Text(label, fontSize = 10.sp)
            }
        }
    }
}

private fun Modifier.dots() = drawBehind {
    val gap = 8.dp.toPx(); var x = 4.dp.toPx()
    while (x < size.width) { var y = 4.dp.toPx(); while (y < size.height) { drawCircle(Brown.copy(alpha = .32f), .45.dp.toPx(), Offset(x, y)); y += gap }; x += gap }
}

@Composable
private fun Eyebrow(text: String) { Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }

@Composable
private fun Hero(account: Boolean, signup: Boolean = false) {
    Column(Modifier.fillMaxWidth().dots().border(.7.dp, Brown).padding(horizontal = 24.dp, vertical = if (account) 28.dp else 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Eyebrow(if (account) "MY FORTUNE ACCOUNT" else LocalDate.now(seoul).format(DateTimeFormatter.ofPattern("yyyy. MM.dd · EEE", Locale.ENGLISH)).uppercase())
        if (account) {
            Text("내 정보", fontFamily = Serif, fontSize = 34.sp)
            Text(if (signup) "나만의 운세와 부적을 위한 계정을 만들어보세요." else "로그인하고 저장해 둔 운세와 부적을 이어서 확인해보세요.", fontSize = 12.sp, lineHeight = 22.sp)
        } else {
            Text("오늘의 불안을", fontFamily = Serif, fontSize = 34.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("행운으로", color = Green, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                Text(" 바꿔볼까요?", fontFamily = Serif, fontSize = 28.sp)
            }
            Text("내 정보에 저장된 생년월일을 바탕으로 살펴본 오늘의 흐름이에요.\n지금 할 수 있는 작은 행동도 함께 찾아드릴게요.", fontSize = 12.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun Action(label: String, busy: Boolean, click: () -> Unit) {
    Column {
    Button(onClick = click, enabled = !busy, shape = RectangleShape, contentPadding = PaddingValues(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Brown, disabledContainerColor = Yellow.copy(alpha = .5f), disabledContentColor = Brown),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).border(.7.dp, Brown)) {
        Text(if (busy) "처리 중…" else label, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(19.dp))
    }
    Box(Modifier.fillMaxWidth().height(3.dp).background(Red))
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, error: String? = null, secret: Boolean = false, email: Boolean = false, enabled: Boolean = true, change: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, shape = RectangleShape,
            textStyle = TextStyle(fontSize = 14.sp, color = Brown), placeholder = { Text(hint, fontSize = 13.sp, color = Color(0xFFB4AAA3)) },
            isError = error != null, visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (email) KeyboardType.Email else KeyboardType.Text),
            trailingIcon = if (secret) { { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "비밀번호 숨기기" else "비밀번호 표시", Modifier.size(18.dp)) } } } else null,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brown, unfocusedBorderColor = Brown, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
        error?.let { Text(it, color = Red, fontSize = 11.sp) }
    }
}

@Composable
private fun Choices(label: String, options: List<String>, selected: Int, enabled: Boolean, error: String? = null, choose: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row { options.forEachIndexed { index, text ->
            Row(Modifier.weight(1f).background(Color.White).border(.7.dp, Brown).clickable(enabled = enabled) { choose(index) }.padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (selected == index) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(16.dp), tint = if (selected == index) Red else Brown.copy(alpha = .6f))
                Text(text, Modifier.padding(start = 8.dp), fontSize = 12.sp)
            }
        } }
        error?.let { Text(it, color = Red, fontSize = 11.sp) }
    }
}

@Composable
private fun Picker(label: String, value: String, hint: String, icon: ImageVector, error: String?, enabled: Boolean, click: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().background(Color.White).border(.7.dp, Brown).clickable(enabled = enabled, onClick = click).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { hint }, Modifier.weight(1f), fontSize = 13.sp); Icon(icon, null, Modifier.size(18.dp))
        }
        error?.let { Text(it, color = Red, fontSize = 11.sp) }
    }
}

@Composable
private fun Account(model: AppModel, settings: () -> Unit) {
    val context = LocalContext.current; val form = model.form
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero(true, model.signup)
        if (model.loggedIn) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Eyebrow("MY ACCOUNT"); Text("다시 만나 반가워요", fontFamily = Serif, fontSize = 26.sp); Text(model.email)
            Action("오늘의 운세 확인", model.busy) { model.page = "home" }
            TextButton(onClick = { model.logout() }, enabled = !model.busy) { Text("로그아웃") }
        } else Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp).fillMaxWidth().background(Color.White).border(.7.dp, Brown)) {
            Box(Modifier.fillMaxWidth().height(5.dp).background(Yellow))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Eyebrow(if (model.signup) "CREATE YOUR ACCOUNT" else "WELCOME BACK")
                Text(if (model.signup) "회원가입" else "다시 만나 반가워요", fontFamily = Serif, fontSize = 26.sp)
                Text(if (model.signup) "기본 정보를 입력하면 바로 오늘의 운세를 확인할 수 있어요." else "이메일과 비밀번호를 입력해 로그인해주세요.", fontSize = 12.sp, lineHeight = 20.sp)
                HorizontalDivider(color = Brown, thickness = .7.dp)
                Field("이메일", form.email, "email@example.com", model.errors["email"], email = true, enabled = !model.busy) { model.form = form.copy(email = it) }
                Field("비밀번호", form.password, if (model.signup) "영문과 숫자를 포함해 8자 이상" else "비밀번호를 입력해주세요", model.errors["password"], secret = true, enabled = !model.busy) { model.form = form.copy(password = it) }
                if (model.signup) {
                    Field("비밀번호 확인", form.confirm, "비밀번호를 한 번 더 입력해주세요", model.errors["confirm"], secret = true, enabled = !model.busy) { model.form = form.copy(confirm = it) }
                    Field("사용자 이름", form.name, "2자 이상 4자 이하", model.errors["name"], enabled = !model.busy) { model.form = form.copy(name = it) }
                    Picker("생년월일", form.birth, "연도-월-일", Icons.Outlined.CalendarToday, model.errors["birth"], !model.busy) {
                        val initial = runCatching { LocalDate.parse(form.birth) }.getOrDefault(LocalDate.of(2000, 1, 1))
                        DatePickerDialog(context, { _, y, m, d -> model.form = model.form.copy(birth = LocalDate.of(y, m + 1, d).toString()) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                            datePicker.minDate = LocalDate.of(1900, 1, 1).atStartOfDay(seoul).toInstant().toEpochMilli()
                            datePicker.maxDate = LocalDate.of(LocalDate.now(seoul).year, 12, 31).atStartOfDay(seoul).toInstant().toEpochMilli()
                        }.show()
                    }
                    Choices("달력 기준", listOf("양력", "음력"), if (form.lunar) 1 else 0, !model.busy) { model.form = form.copy(lunar = it == 1, leap = null) }
                    if (form.lunar) Choices("윤달 여부", listOf("평달", "윤달"), when (form.leap) { false -> 0; true -> 1; null -> -1 }, !model.busy, model.errors["leap"]) { model.form = form.copy(leap = it == 1) }
                    Picker("태어난 시간  ·  선택", form.time, "--:--", Icons.Outlined.Schedule, null, !model.busy) {
                        val parts = form.time.split(":")
                        TimePickerDialog(context, { _, h, m -> model.form = model.form.copy(time = String.format(Locale.ROOT, "%02d:%02d", h, m)) }, parts.firstOrNull()?.toIntOrNull() ?: 12, parts.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
                    }
                    if (form.time.isNotBlank()) Text("시간 모름 · 입력 지우기", Modifier.clickable(enabled = !model.busy) { model.form = form.copy(time = "") }, fontSize = 11.sp)
                    Choices("성별", listOf("남성", "여성"), when (form.gender) { "MALE" -> 0; "FEMALE" -> 1; else -> -1 }, !model.busy, model.errors["gender"]) { model.form = form.copy(gender = if (it == 0) "MALE" else "FEMALE") }
                }
                Action(if (model.signup) "회원가입 완료" else "로그인", model.busy) { model.authenticate() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (model.signup) "이미 계정이 있나요?" else "처음 방문하셨나요?", fontSize = 11.sp)
                    TextButton(onClick = { model.switchAuth() }, enabled = !model.busy) { Text(if (model.signup) "로그인" else "회원가입", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = settings, enabled = !model.busy) { Text("서버 설정", fontSize = 11.sp) }
            if (!model.loggedIn) TextButton(onClick = { model.showPreview() }, enabled = !model.busy) { Text("디자인 미리보기", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun Home(model: AppModel, notice: (String) -> Unit) {
    var dropdown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("WHO IS THIS FORTUNE FOR?")
            Row {
                Row(Modifier.weight(1f).background(Yellow).border(.7.dp, Brown).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(20.dp)); Column(Modifier.padding(start = 8.dp)) { Text("나의 부적", fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("저장된 내 정보로 보기", fontSize = 9.sp) }
                }
                Row(Modifier.weight(1f).background(Color.White).border(.7.dp, Brown).clickable { notice("친구에게 선물 화면은 후속 구현 대상입니다.") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CardGiftcard, null, Modifier.size(20.dp)); Column(Modifier.padding(start = 8.dp)) { Text("친구에게 선물", fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("친구의 정보로 만들기", fontSize = 9.sp) }
                }
            }
        }
        Hero(false)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                !model.loggedIn && !model.preview -> { Text("로그인하고 나의 오늘 운세를 확인해보세요.", fontFamily = Serif, fontSize = 20.sp); Action("로그인하러 가기", false) { model.page = "account" }; TextButton(onClick = { model.showPreview() }) { Text("예시 운세 미리보기") } }
                model.fortune != null -> FortuneCard(model.fortune!!)
                model.busy -> Text("오늘의 운세를 불러오고 있어요…")
                else -> { Text("오늘의 운세를 불러오지 못했습니다."); Action("다시 불러오기", model.busy) { model.loadFortune() } }
            }
        }
        if (model.loggedIn || model.preview) {
            HorizontalDivider(color = Brown, thickness = .7.dp)
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("02", Modifier.background(Yellow).border(.7.dp, Brown).padding(12.dp), fontSize = 11.sp)
                Eyebrow("TURN WORRY INTO LUCK")
                Text("지금 마음에 걸리는 일은 무엇인가요?", fontFamily = Serif, fontSize = 24.sp, lineHeight = 36.sp)
                Text("상황과 감정을 알려주면 오늘의 운세에 맞춰 해석해드려요.", fontSize = 12.sp, lineHeight = 22.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("상황 선택", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box {
                        Row(Modifier.fillMaxWidth().background(Color.White).border(.7.dp, Brown).clickable(enabled = !model.busy) { dropdown = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(model.category, Modifier.weight(1f), fontSize = 13.sp); Icon(Icons.Outlined.ExpandMore, null) }
                        DropdownMenu(dropdown, { dropdown = false }) { listOf("전체", "연애", "금전", "취업", "학업", "건강", "인간관계").forEach { category -> DropdownMenuItem(text = { Text(category) }, onClick = { model.category = category; dropdown = false }) } }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("나의 고민", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(model.concern, { if (it.length <= 200) model.concern = it }, Modifier.fillMaxWidth().heightIn(min = 130.dp), enabled = !model.busy, shape = RectangleShape,
                        placeholder = { Text("첫 면접을 앞두고 있는데, 준비한 말을 잘 전달하지 못할까 봐 걱정돼요.", fontSize = 12.sp) }, textStyle = TextStyle(color = Brown, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Brown, focusedBorderColor = Brown, unfocusedContainerColor = Color.White, focusedContainerColor = Color.White))
                    Text("${model.concern.length} / 200", Modifier.align(Alignment.End), fontSize = 10.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("지금의 마음", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    listOf(listOf("불안해요", "우울해요"), listOf("답답해요", "용기가 필요해요")).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { mood -> Row(Modifier.weight(1f).background(if (model.mood == mood) Red else Color.White).border(.7.dp, Brown).clickable(enabled = !model.busy) { model.mood = mood }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val color = if (model.mood == mood) Color.White else Brown
                            Icon(if (model.mood == mood) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(15.dp), tint = color)
                            Text(mood, Modifier.padding(start = 6.dp), color = color, fontSize = 11.sp)
                        } }
                    } }
                }
                Action("AI 맞춤 해석 받기", model.busy) { model.interpret() }
                model.interpretation?.let { result -> Column(Modifier.fillMaxWidth().background(Color.White).border(.7.dp, Brown).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Eyebrow("A LITTLE LUCK FOR YOU"); Text(result.optString("day_message"), fontFamily = Serif, fontSize = 21.sp)
                    Text(result.optString("action_advice"), fontSize = 13.sp, lineHeight = 22.sp)
                    Action("부적 만들기", false) { notice("부적 편집기는 후속 구현 대상입니다.\n예시 템플릿: 행운 · 용기 · 응원") }
                } }
            }
        }
    }
}

@Composable
private fun FortuneCard(fortune: JSONObject) {
    Column(Modifier.fillMaxWidth().background(Color.White).border(.7.dp, Brown)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { Eyebrow("TODAY'S FORTUNE"); Text(fortune.optString("headline"), fontFamily = Serif, fontSize = 23.sp, lineHeight = 34.sp) }
            Text(fortune.optInt("total_score").toString(), color = Red, fontFamily = Serif, fontSize = 42.sp); Text("/100", fontSize = 9.sp)
        }
        HorizontalDivider(color = Brown, thickness = .7.dp)
        Text(fortune.optString("total_comment"), Modifier.padding(18.dp), fontSize = 12.sp, lineHeight = 22.sp)
        HorizontalDivider(color = Brown, thickness = .7.dp)
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("애정운" to "love_score", "금전운" to "money_score", "직장운" to "career_score", "학업/성적운" to "study_score", "건강운" to "health_score", "총운" to "total_score").forEach { (label, key) ->
                val score = fortune.optInt(key).coerceIn(0, 100)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, Modifier.width(60.dp), fontSize = 10.sp)
                    Box(Modifier.weight(1f).height(5.dp).border(.6.dp, Brown)) { Box(Modifier.fillMaxWidth(score / 100f).fillMaxHeight().background(Yellow)) }
                    Text(score.toString(), Modifier.width(20.dp), fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFFFF0D0))) {
            listOf("행운 숫자" to String.format(Locale.ROOT, "%02d", fortune.optInt("lucky_number")), "행운의 색" to fortune.optString("lucky_color"), "오늘의 행동" to fortune.optString("today_action")).forEach { (label, value) ->
                Column(Modifier.weight(1f).heightIn(min = 82.dp).border(.6.dp, Brown).padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, fontSize = 9.sp); Text(value, fontFamily = Serif, fontSize = 17.sp, lineHeight = 24.sp) }
            }
        }
    }
}

@Composable
private fun Library() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Eyebrow("MY LITTLE LUCK"); Text("보관함", fontFamily = Serif, fontSize = 34.sp)
        Text("보관함 API 연결 전 예시 화면입니다.", fontSize = 12.sp)
        listOf("오늘도 잘될 거야" to "용기를 주는 부적", "행운이 찾아오는 날" to "나를 위한 응원").forEach { (title, caption) -> Column(Modifier.fillMaxWidth().background(Yellow).border(.7.dp, Brown).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("福", fontFamily = Serif, fontSize = 38.sp, color = Red); Text(title, fontFamily = Serif, fontSize = 24.sp); Text(caption, fontSize = 12.sp) } }
    }
}
