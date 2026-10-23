package app.photorelativity

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import kotlin.math.sqrt

const val MAXV = 999_999_999_999_999_999L
const val GAP = 10_000_000_000_000L
val rx = Regex("^(\\d{18})[A-Z]*\\.[^.]+$")

class Photo(var doc: DocumentFile, var vec: FloatArray? = null) {
    val name get() = doc.name ?: ""
    val ext get() = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
}
class G(var num: Long) { val items = mutableListOf<Photo>() }

fun decode(c: Context, u: Uri, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
    o.inJustDecodeBounds = false; o.inSampleSize = s
    return c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
}
fun show(c: Context, u: Uri): ImageBitmap? {
    val b = decode(c, u, 1000) ?: return null
    val deg = c.contentResolver.openInputStream(u)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    } ?: 0f
    val r = if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    return r.asImageBitmap()
}
// vetor de similaridade: miniatura 16x16 RGB, centralizada e normalizada
fun feat(c: Context, u: Uri): FloatArray? {
    val b = decode(c, u, 128) ?: return null
    val t = Bitmap.createScaledBitmap(b, 16, 16, true)
    val px = IntArray(256); t.getPixels(px, 0, 16, 0, 0, 16, 16)
    val v = FloatArray(768)
    px.forEachIndexed { i, p -> v[3 * i] = Color.red(p).toFloat(); v[3 * i + 1] = Color.green(p).toFloat(); v[3 * i + 2] = Color.blue(p).toFloat() }
    val m = v.average().toFloat(); for (i in v.indices) v[i] -= m
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
    for (i in v.indices) v[i] /= n
    return v
}
fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
fun lab(i: Int): String { var x = i; var s = ""; do { s = ('A' + x % 26) + s; x = x / 26 - 1 } while (x >= 0); return s }

class Sorter(val ctx: Context, val root: DocumentFile) {
    val groups = ArrayList<G>(); val pending = ArrayDeque<Photo>()
    var cur: Photo? = null; var probe = 0
    var lo = 0; var hi = 0; var loSet = false; var hiSet = false; var step = 1
    val placed get() = groups.sumOf { it.items.size }

    fun load(prog: (Int, Int) -> Unit) {
        val all = root.listFiles().filter { it.type?.startsWith("image/") == true }.sortedBy { it.name }
        val pl = mutableMapOf<Long, MutableList<Photo>>()
        all.forEachIndexed { i, d ->
            prog(i + 1, all.size)
            val p = Photo(d); val m = rx.matchEntire(p.name)
            if (m == null) pending.add(p) else { p.vec = feat(ctx, d.uri); pl.getOrPut(m.groupValues[1].toLong()) { mutableListOf() }.add(p) }
        }
        pl.toSortedMap().forEach { (n, l) -> groups.add(G(n).also { it.items.addAll(l) }) }
        startNext()
    }
    fun startNext() {
        val c = pending.removeFirstOrNull(); cur = c
        if (c == null) return
        c.vec = feat(ctx, c.doc.uri)
        if (groups.isEmpty()) { insert(c, 0); startNext(); return }
        lo = 0; hi = groups.size; loSet = false; hiSet = false; step = 1
        val v = c.vec
        probe = if (v == null) groups.size / 2 else groups.indices.maxBy { gi -> groups[gi].items.maxOf { p -> p.vec?.let { dot(v, it) } ?: -2f } }
    }
    // a>0: nova é mais recente que a da esquerda; a<0: mais antiga; 0: mesmo período
    fun answer(a: Int) {
        val c = cur ?: return
        if (a == 0) { groups[probe].items.add(c); relabel(groups[probe]); startNext(); return }
        if (a > 0) { lo = probe + 1; loSet = true } else { hi = probe; hiSet = true }
        if (lo >= hi) { insert(c, lo); startNext(); return }
        probe = when {
            loSet && hiSet -> (lo + hi) / 2
            loSet -> minOf(lo + step - 1, hi - 1).also { step *= 2 }
            else -> maxOf(hi - step, lo).also { step *= 2 }
        }
    }
    fun pick(pos: Int): Long? {
        val n = groups.size
        val v = when {
            n == 0 -> MAXV / 2
            pos == 0 -> groups[0].num.let { maxOf(it - GAP, it / 2) }
            pos == n -> groups[n - 1].num.let { minOf(it + GAP, (it + MAXV) / 2) }
            else -> (groups[pos - 1].num + groups[pos].num) / 2
        }
        return if (v < 0 || v > MAXV || groups.any { it.num == v }) null else v
    }
    fun insert(c: Photo, pos: Int) {
        val num = pick(pos) ?: run { renumber(); pick(pos)!! }
        val g = G(num); g.items.add(c); groups.add(pos, g); relabel(g)
    }
    fun relabel(g: G) = g.items.forEachIndexed { i, p ->
        val n = "%018d".format(g.num) + (if (g.items.size > 1) lab(i) else "") + p.ext
        if (p.name != n) p.doc.renameTo(n)
    }
    fun renumber() {
        var k = 0
        groups.forEach { g -> g.items.forEach { it.doc.renameTo("t${k++}${it.ext}") } }
        val st = MAXV / (groups.size + 1)
        groups.forEachIndexed { i, g -> g.num = st * (i + 1); relabel(g) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { MaterialTheme { App() } } }
}

@Composable fun Pic(p: Photo, mod: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, p.doc.uri) { value = withContext(Dispatchers.IO) { show(ctx, p.doc.uri) } }
    bmp?.let { Image(it, null, mod, contentScale = ContentScale.Fit) }
}

@Composable fun App() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var sorter by remember { mutableStateOf<Sorter?>(null) }
    var status by remember { mutableStateOf("Escolha a pasta com as fotos") }
    var tick by remember { mutableIntStateOf(0) }; var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, 3)
            scope.launch {
                sorter = withContext(Dispatchers.IO) {
                    Sorter(ctx, DocumentFile.fromTreeUri(ctx, uri)!!).also { it.load { i, n -> status = "Analisando $i/$n" } }
                }
            }
        }
    }
    fun answer(a: Int) { val s = sorter ?: return; if (busy) return; busy = true
        scope.launch { withContext(Dispatchers.IO) { s.answer(a) }; tick++; busy = false } }
    val t = tick
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val s = sorter; val c = s?.cur
        if (s == null) { Text(status); Button({ pick.launch(null) }) { Text("Escolher pasta") } }
        else if (c == null) Text("Tudo organizado! ${s.placed} fotos.")
        else {
            Text("Toque na foto MAIS RECENTE · faltam ${s.pending.size + 1}")
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pic(s.groups[s.probe].items[0], Modifier.weight(1f).fillMaxHeight().clickable { answer(-1) })
                Pic(c, Modifier.weight(1f).fillMaxHeight().clickable { answer(1) })
            }
            Button({ answer(0) }, Modifier.fillMaxWidth()) { Text("Mesmo período") }
        }
    }
}
