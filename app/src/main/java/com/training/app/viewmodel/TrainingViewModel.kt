package com.training.app.viewmodel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.training.app.data.RacePlan
import com.training.app.data.TrainingRepository
import com.training.app.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TodayState(val day:ProgramDayEntity?=null,val exercises:List<ProgramExerciseRow> = emptyList(),val session:SessionEntity?=null)
class TrainingViewModel(private val repo:TrainingRepository):ViewModel(){
    private val naturalDay=repo.today().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    private val todaySession=repo.sessionForDate(LocalDate.now().toString())
    // "Switch workout" lets the user override which program day today uses, before a session
    // exists — once a session is created it carries its own programDayId (set from whichever day
    // was showing at that moment), which then wins over both the override and the natural
    // weekday lookup, so switching mid-session can't silently swap the exercise list out from
    // under logged sets.
    private val overrideDayId = MutableStateFlow<String?>(null)
    fun switchDay(dayId: String) { overrideDayId.value = dayId }

    // Duration dial: 15/30/45min. Only meaningful before a session exists — once ensureSession
    // stamps it onto the SessionEntity, that stored value is what the active circuit screen and
    // WorkoutSelector actually key off, not this live picker (mirrors overrideDayId's pattern).
    val selectedDuration = MutableStateFlow(45)
    fun selectDuration(minutes: Int) { selectedDuration.value = minutes }

    private val effectiveDay: Flow<ProgramDayEntity?> = combine(naturalDay, todaySession, overrideDayId) { natural, session, override -> Triple(natural, session, override) }
        .flatMapLatest { (natural, session, override) ->
            val targetId = session?.programDayId ?: override ?: natural?.id
            when {
                targetId == null -> flowOf(null)
                targetId == natural?.id -> flowOf(natural)
                else -> repo.dayById(targetId)
            }
        }
    val today=effectiveDay.flatMapLatest { d -> if(d==null) flowOf(TodayState()) else combine(repo.exercises(d.id),todaySession){ex,s->TodayState(d,ex,s)} }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),TodayState())
    val sessionSummaries=repo.sessionSummaries().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    val allSetDetails=repo.allSetDetails().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    val latestWeight=repo.metrics().map{it.lastOrNull()}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    fun logWeight(weightKg: Double, source: String = "manual") = viewModelScope.launch { repo.logWeight(weightKg, source) }
    val goals=repo.goals().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    fun saveGoals(dailyCalories: Int?, dailyDistanceKm: Double?, dailySteps: Int?) = viewModelScope.launch { repo.saveGoals(dailyCalories, dailyDistanceKm, dailySteps) }
    val todaySleep=repo.sleepFor(LocalDate.now().toString()).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)
    fun logSleep(hours: Double, date: String = LocalDate.now().toString()) = viewModelScope.launch { repo.logSleep(hours, date) }
    fun sleepFor(date: String) = repo.sleepFor(date)
    // Set by MainActivity when the 9am sleep-reminder notification is tapped, so Today can pop the
    // entry dialog open with no extra navigation — cleared once Today has acted on it, so it doesn't
    // re-fire on the next recomposition or a config change.
    val requestSleepEntry = MutableStateFlow(false)
    fun triggerSleepEntry() { requestSleepEntry.value = true }
    fun clearSleepEntryRequest() { requestSleepEntry.value = false }
    init { viewModelScope.launch { repo.seed() } }
    fun ensureSession(day:ProgramDayEntity, callback:(SessionEntity)->Unit)=viewModelScope.launch { callback(repo.ensureToday(day, selectedDuration.value)) }
    fun saveFinisher(session:SessionEntity, durationSec:Int, roundsCompleted:Int?)=viewModelScope.launch{repo.saveFinisher(session,durationSec,roundsCompleted)}
    fun logSet(session:String,exercise:String,number:Int,weight:Double?,reps:Int,isExtra:Boolean=false)=viewModelScope.launch{repo.completeSet(session,exercise,number,weight,reps,isExtra)}
    fun unlogSet(session:String,exercise:String,number:Int)=viewModelScope.launch{repo.uncompleteSet(session,exercise,number)}
    fun pauseSession(session:SessionEntity)=viewModelScope.launch{repo.pauseSession(session)}
    fun resumeSession(session:SessionEntity)=viewModelScope.launch{repo.resumeSession(session)}
    fun resetSessionTimer(session:SessionEntity)=viewModelScope.launch{repo.resetSessionTimer(session)}
    fun setsFor(session:String,exercise:String)=repo.sets(session,exercise)
    fun lastPerformance(exerciseId:String,sessionId:String)=repo.lastPerformance(exerciseId,sessionId)
    fun sessionDetails(sessionId:String)=repo.sessionSetDetails(sessionId)
    suspend fun hasLoggedSets(sessionId:String)=repo.hasLoggedSets(sessionId)
    fun finishWorkout(session:SessionEntity,burnRating:Int)=viewModelScope.launch{repo.save(session.copy(burnRating=burnRating))}
    fun toggleCardio(session:SessionEntity)=viewModelScope.launch{repo.save(session.copy(cardioDone=!session.cardioDone))}

    // --- 5K race plan ---
    // Null baseline means the plan hasn't been generated yet — TodayScreen/ProfileScreen decide
    // whether to try Health Connect first or prompt the user directly (this ViewModel deliberately
    // never touches Health Connect itself, same as everywhere else in this codebase — that only
    // ever happens from a Composable with a real Context).
    val raceBaseline = repo.raceBaseline().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setRaceBaseline(easyPaceSecPerKm: Int, source: String) = viewModelScope.launch {
        val today = LocalDate.now()
        val planStart = today.minusDays((today.dayOfWeek.value - 1).toLong()).toString()
        repo.setRaceBaseline(easyPaceSecPerKm, source, planStart)
        repo.ensureRacePlanSeeded(easyPaceSecPerKm)
    }
    // Week number is derived from the fixed plan-start Monday, not re-picked each time — null once
    // the plan hasn't started yet (baseline unset) or has run past its 12 weeks.
    val currentRaceWeekNumber: StateFlow<Int?> = raceBaseline.map { baseline ->
        val start = baseline?.planStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@map null
        val weekNumber = ChronoUnit.WEEKS.between(start, LocalDate.now()).toInt() + 1
        weekNumber.takeIf { it in 1..RacePlan.TOTAL_WEEKS }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun racePlanWeek(weekNumber: Int) = repo.racePlanWeek(weekNumber)
    fun racePlanSessionForDate(date: String) = repo.racePlanSessionForDate(date)
    fun logRacePlanSession(weekId: String, date: String, pace: String?, distanceKm: Double?, durationMin: Double?, completed: Boolean = true) =
        viewModelScope.launch { repo.logRacePlanSession(weekId, date, pace, distanceKm, durationMin, completed) }
    // Safe to call unconditionally whenever a screen loads the current week — the repo only ever
    // actually computes and applies it once (adjustmentComputed), and week 1 has no prior week to
    // adjust from in the first place.
    fun ensureWeekAdjustmentComputed(weekNumber: Int) = viewModelScope.launch { repo.ensureWeekAdjustmentComputed(weekNumber) }
}
