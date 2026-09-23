package com.training.app.data

import com.training.app.data.local.*
import java.util.UUID

/** Fixed Week-1 Push/Pull/Legs template. IDs are deterministic UUIDs for safe retries. */
object Seeds {
    private fun id(value: String) = UUID.nameUUIDFromBytes("training:$value".toByteArray()).toString()
    // Legs sits on Friday so the hardest session lands right before the weekend's two recovery days.
    // roundsPrescribed is the circuit's full round count at the default (45min) duration — every
    // exercise in a day shares this same number, since a circuit means one set of each per round,
    // not each exercise carrying its own independent set count the way straight sets did.
    val days = listOf(
        ProgramDayEntity(id("day-mon"), 1, "Push", "Chest, shoulders, triceps. One circuit, minimal rest between moves.", roundsPrescribed = 4),
        ProgramDayEntity(id("day-tue"), 2, "Pull", "Back, biceps. Pull from every angle, squeeze don't yank.", roundsPrescribed = 4),
        ProgramDayEntity(id("day-wed"), 3, "Push", "Same circuit as Monday. Compare your numbers.", roundsPrescribed = 4),
        ProgramDayEntity(id("day-thu"), 4, "Pull", "Same circuit as Tuesday. Compare your numbers.", roundsPrescribed = 4),
        ProgramDayEntity(id("day-fri"), 5, "Legs", "The big burn. Slow the descent, full depth.", roundsPrescribed = 4)
    )
    // slug defaults to a guessed kebab-case of the name, but MuscleWiki's real per-exercise
    // slugs don't always match (different word order, equipment naming, etc.) — verified against
    // musclewiki.com search results, pass the real slug explicitly whenever it differs from the
    // guess. imageSlug is separate: the exercise-images Storage bucket was uploaded under the
    // guessed (pre-correction) names, so it doesn't always match the corrected MuscleWiki slug either.
    private val imageBase = "${com.training.app.BuildConfig.SUPABASE_URL}/storage/v1/object/public/exercise-images/"
    private fun exercise(key:String,name:String,muscle:String,equipment:String="dumbbell",slug:String?=null,imageSlug:String?=null) = ExerciseEntity(id(key),name,muscle,equipment,slug ?: name.lowercase().replace(" ","-"), imageUrl = "$imageBase${imageSlug ?: name.lowercase().replace(" ","-")}.png")
    val exercises = listOf(
        // Floor Press replaces Dumbbell Bench Press (same pressing pattern, no bench needed) and
        // Push-Up replaces Incline Dumbbell Press (equipment-free) — per the circuit-training
        // pivot away from bench dependency. Both slugs confirmed against musclewiki.com search
        // results: musclewiki.com/exercise/dumbbell-floor-press and musclewiki.com/exercise/push-up.
        exercise("floor-press","Floor Press","chest",slug="dumbbell-floor-press"), exercise("ohp","Dumbbell Overhead Press","shoulders"), exercise("pushup","Push-Up","chest","bodyweight",slug="push-up"), exercise("triceps","Triceps Pushdown / DB Overhead Extension","triceps",slug="dumbbell-overhead-tricep-extension",imageSlug="triceps-pushdown"),
        exercise("pulldown","Lat Pulldown","lats","cable",slug="machine-pulldown"), exercise("row","One-Arm Dumbbell Row","lats",slug="dumbbell-single-arm-row"), exercise("cable-row","Seated Cable Row","lats","cable",slug="machine-seated-cable-row"), exercise("curl","Dumbbell Bicep Curl","biceps",slug="dumbbell-curl"),
        exercise("squat","Goblet Squat (DB)","quads",slug="dumbbell-goblet-squat",imageSlug="goblet-squat"), exercise("rdl","Dumbbell Romanian Deadlift","hamstrings"), exercise("lunge","Dumbbell Walking Lunges","quads",slug="dumbbell-forward-lunge"), exercise("calf","Standing Calf Raise","calves",slug="dumbbell-calf-raise")
    )
    fun program() = buildList<ProgramExerciseEntity> {
        fun add(day:String,key:String,sets:Int,range:String,kg:Double?,reps:Int) = add(ProgramExerciseEntity(id("$day-$key"),id(day),id(key),count{it.programDayId==id(day)}+1,sets,range,kg,reps))
        // Plain hyphens, not en dashes, in these rep ranges — keyboard typable, per the voice guide.
        // Every exercise now carries the same round count (4, matching each day's
        // roundsPrescribed) — a circuit's whole point is that everything advances together,
        // rather than each exercise running its own independent set count.
        fun push(day:String) { add(day,"floor-press",4,"8-12",14.0,10); add(day,"ohp",4,"8-12",12.0,10); add(day,"pushup",4,"10-15",null,12); add(day,"triceps",4,"12-15",15.0,12) }
        fun pull(day:String) { add(day,"pulldown",4,"10-12",30.0,10); add(day,"row",4,"10-12 / side",14.0,10); add(day,"cable-row",4,"10-12",30.0,10); add(day,"curl",4,"12-15",8.0,12) }
        push("day-mon"); pull("day-tue"); push("day-wed"); pull("day-thu"); add("day-fri","squat",4,"10-12",16.0,10); add("day-fri","rdl",4,"10-12",14.0,10); add("day-fri","lunge",4,"10-12 / leg",8.0,10); add("day-fri","calf",4,"15-20",12.0,15)
    }
}
