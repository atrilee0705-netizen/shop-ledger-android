package cn.shopledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shopledger.data.*
import cn.shopledger.backup.SettlementScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as LedgerApp
    val ledger = app.ledger
    private val dao = ledger.dao
    val date = MutableStateFlow(Rules.today())
    private val clock = MutableStateFlow(Rules.now())
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    val employees = dao.employeesFlow().stateIn(viewModelScope, sharing, emptyList())
    val tasks = dao.tasksFlow().stateIn(viewModelScope, sharing, emptyList())
    val settings = dao.settingsFlow().map { it ?: Settings() }.stateIn(viewModelScope, sharing, Settings())
    val localState = dao.localStateFlow().map { it ?: LocalState() }.stateIn(viewModelScope, sharing, LocalState())
    val recovery = dao.recoveryFlow().stateIn(viewModelScope, sharing, null)
    val settlements = dao.settlementsFlow().stateIn(viewModelScope, sharing, emptyList())
    val items = dao.settlementItemsFlow().stateIn(viewModelScope, sharing, emptyList())
    val deliveries = dao.deliveryFlow().stateIn(viewModelScope, sharing, emptyList())
    val adjustments = dao.adjustmentsFlow().stateIn(viewModelScope, sharing, emptyList())
    val pendingEntries = dao.pendingEntriesFlow().stateIn(viewModelScope, sharing, emptyList())
    val firstEntry = dao.firstEntryFlow().stateIn(viewModelScope, sharing, null)
    val dailyTotal = date.flatMapLatest { dao.dailyTotal(Rules.start(it), Rules.start(it.plusDays(1))) }
        .stateIn(viewModelScope, sharing, DailyTotal(0, 0))
    val dailyEmployees = date.flatMapLatest { dao.employeeTotals(Rules.start(it), Rules.start(it.plusDays(1))) }
        .stateIn(viewModelScope, sharing, emptyList())
    val counts = date.flatMapLatest { dao.taskCounts(Rules.start(it), Rules.start(it.plusDays(1))) }
        .stateIn(viewModelScope, sharing, emptyList())
    val events = Channel<String>(Channel.BUFFERED)
    init {
        viewModelScope.launch {
            var previousDay = Rules.today()
            while (isActive) {
                clock.value = Rules.now()
                val today = Rules.today()
                if (date.value == previousDay && today != previousDay) date.value = today
                previousDay = today
                delay(15_000)
            }
        }
    }
    fun selectDate(value: LocalDate) { Rules.checkTime(Rules.start(value)); date.value = value }
    fun entries(id: String, date: LocalDate) = dao.entryFlow(id, Rules.start(date), Rules.start(date.plusDays(1)))
    fun rangeEntries(from: LocalDate, through: LocalDate) = dao.rangeEntriesFlow(Rules.start(from), Rules.start(through.plusDays(1)))
    fun action(message: String, onSuccess: () -> Unit = {}, onComplete: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                SettlementScheduler.sendReports(app)
                clock.value = Rules.now()
                onSuccess()
                if (message.isNotEmpty()) events.send(message)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { events.send(e.message?.take(200) ?: "操作失败，请检查输入") }
            finally { onComplete() }
        }
    }
}
