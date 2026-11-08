package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable

/** What a fact is about. Facts are taken out once per session; templates choose which kinds each section uses. */
@Serializable
enum class FactKind(val label: String, val hint: String) {
    CONTEXT("Context", "who is present, setting, consent, for telehealth the patient's location, identity check and connection"),
    REASON("Reason for review", "why the patient is being seen or was referred, the main complaint"),
    HISTORY("History", "events and course since the last review, or the history of the presenting illness"),
    SYMPTOMS("Symptoms", "mood, anxiety, psychotic, sleep, appetite and other symptoms the patient describes"),
    ADHD("ADHD symptoms", "inattention, hyperactivity, impulsivity, their onset in childhood and their impact now"),
    PAST_PSYCH("Past psychiatric history", "earlier illnesses, admissions, treatments, self-harm history"),
    PHYSICAL("Physical health", "medical conditions, blood pressure, pulse, weight, bloods, ECG, cardiac history"),
    MEDICATIONS("Medications", "each medicine with dose, recent changes, adherence, benefit and side effects"),
    ALLERGIES("Allergies", "allergies and adverse reactions"),
    SUBSTANCES("Substance use", "alcohol, cannabis, methamphetamine, other drugs, nicotine, amounts"),
    FAMILY("Family history", "illness in the family, including sudden cardiac death"),
    PERSONAL("Personal history", "development, schooling, work, relationships"),
    SOCIAL("Social situation", "housing, supports, finances, daily activities"),
    FORENSIC("Forensic history", "charges, convictions, police contact"),
    COLLATERAL("Collateral", "what family, staff or records said"),
    MSE_APPEARANCE("Appearance and behaviour", "appearance, engagement, eye contact, psychomotor activity"),
    MSE_SPEECH("Speech", "rate, volume, tone, amount"),
    MSE_MOOD("Mood and affect", "mood in the patient's words and affect as observed"),
    MSE_THOUGHT("Thought", "form and content of thought, delusions, preoccupations, suicidal or homicidal thoughts"),
    MSE_PERCEPTION("Perception", "hallucinations and other perceptual disturbance"),
    MSE_COGNITION("Cognition", "orientation, attention, memory"),
    MSE_INSIGHT("Insight and judgement", "understanding of illness and need for treatment"),
    RISK_SELF("Risk to self", "suicidal ideation, intent, plans, self-harm, neglect"),
    RISK_OTHERS("Risk to others", "aggression, violence, threats"),
    RISK_FROM_OTHERS("Vulnerability", "risk from others, exploitation, abuse"),
    PROTECTIVE("Protective factors", "reasons for living, supports, engagement"),
    MEASURES("Measures", "rating scales, scores and test results"),
    LEGAL_STATUS("Legal status", "Mental Health Act status, orders, leave"),
    WARD("Ward", "observation level, ward events, nursing reports"),
    IMPRESSION("Impression", "the clinician's impression or diagnosis as stated"),
    PLAN("Plan", "what was decided or proposed"),
}

/** One fact taken from the transcript, with the exact words that support it and where they were said. */
@Serializable
data class Fact(
    val id: String,
    val kind: FactKind,
    val text: String,
    val quote: String,
    /** Line number in the transcript the facts were taken from (1-based, as shown to the model). */
    val line: Int,
    val startMs: Long,
    val endMs: Long,
    val removed: Boolean = false,
    val edited: Boolean = false,
)

/** A fact the model offered that the checks threw out, kept so the Lab and Fady can see why. */
@Serializable
data class RejectedFact(val raw: String, val reason: String)

/** All facts of one session, taken from one run's corrected lines. */
@Serializable
data class FactSet(
    val sessionId: String,
    val runId: String,
    val runSeal: String,
    val model: String,
    val facts: List<Fact>,
    val rejected: List<RejectedFact> = emptyList(),
    val createdAtMillis: Long = 0,
    val tookMs: Long = 0,
    /** When Fady last edited, removed or restored a fact (0: as the model left them). */
    val changedAtMillis: Long = 0,
) {
    val active: List<Fact> get() = facts.filterNot { it.removed }
}

@Serializable
enum class SectionForm { PROSE, BULLETS, NUMBERED }

@Serializable
data class NoteSection(
    val id: String,
    val title: String,
    /** What Fady wants in this section, in his words; given to the model as the instruction. */
    val instruction: String,
    val kinds: List<FactKind>,
    val form: SectionForm = SectionForm.PROSE,
    val maxWords: Int = 200,
    /** With no facts for it: leave the section out (true) or write "Not assessed." (false). */
    val omitWhenEmpty: Boolean = true,
    /** Uses every fact (for an impression or formulation that ties the whole note together). */
    val allFacts: Boolean = false,
)

@Serializable
data class NoteTemplate(
    val id: String,
    val name: String,
    val sections: List<NoteSection>,
    val style: String = Templates.STYLE,
    val maxWords: Int = 1000,
    /** Built into Scriber; can be changed, and reset to how it came. */
    val builtIn: Boolean = false,
    val updatedAtMillis: Long = 0,
)

/** One sentence (or list item) of a note, with the facts it rests on and anything worth a look. */
@Serializable
data class NoteSentence(val text: String, val factIds: List<String>, val warning: String? = null)

@Serializable
data class NoteSectionText(val sectionId: String, val title: String, val form: SectionForm, val sentences: List<NoteSentence>)

/**
 * A note as the model wrote it, sealed like a transcript; Fady's own version of the text sits beside
 * it in [editedText] and never replaces it.
 */
@Serializable
data class Note(
    val id: String,
    val sessionId: String,
    val templateId: String,
    val templateName: String,
    val factSetCreatedAt: Long,
    val sections: List<NoteSectionText>,
    val model: String,
    val createdAtMillis: Long,
    val tookMs: Long = 0,
    val editedText: String? = null,
    val editedAtMillis: Long = 0,
) {
    val text: String get() = editedText ?: NoteText.render(sections)
    val warnings: Int get() = sections.sumOf { s -> s.sentences.count { it.warning != null } }
}

object NoteText {
    fun render(sections: List<NoteSectionText>): String = buildString {
        for (s in sections) {
            if (s.sentences.isEmpty()) continue
            if (isNotEmpty()) append("\n\n")
            append(s.title).append('\n')
            when (s.form) {
                SectionForm.PROSE -> append(s.sentences.joinToString(" ") { it.text })
                SectionForm.BULLETS -> append(s.sentences.joinToString("\n") { "- " + it.text })
                SectionForm.NUMBERED -> append(s.sentences.mapIndexed { i, x -> "${i + 1}. ${x.text}" }.joinToString("\n"))
            }
        }
    }

    fun words(text: String): Int = text.split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) }
}

object Templates {
    const val STYLE = "Write as a consultant psychiatrist in Australia and New Zealand: cohesive clinical prose, not a registrar's list. " +
        "Australian English spelling. Third person. Past tense for history, present tense for the mental state. " +
        "Describe risk qualitatively, as a formulation of the factors and their context; never label risk as high, moderate or low. " +
        "Keep ward terms as they were said (for example 15/60 observations). Do not add anything that is not in the facts."

    private val MSE = listOf(FactKind.MSE_APPEARANCE, FactKind.MSE_SPEECH, FactKind.MSE_MOOD, FactKind.MSE_THOUGHT, FactKind.MSE_PERCEPTION, FactKind.MSE_COGNITION, FactKind.MSE_INSIGHT)
    private val RISK = listOf(FactKind.RISK_SELF, FactKind.RISK_OTHERS, FactKind.RISK_FROM_OTHERS, FactKind.PROTECTIVE)

    private fun s(id: String, title: String, instruction: String, kinds: List<FactKind>, form: SectionForm = SectionForm.PROSE, maxWords: Int = 200, omit: Boolean = true, all: Boolean = false) =
        NoteSection(id, title, instruction, kinds, form, maxWords, omit, all)

    private val mseSection = s("mse", "Mental state examination", "One paragraph in the usual order: appearance and behaviour, speech, mood and affect, thought form and content, perception, cognition, insight and judgement. Leave out any part with no facts.", MSE, maxWords = 180, omit = false)
    private val riskSection = s("risk", "Risk", "A qualitative formulation: the static and dynamic factors, current thoughts and behaviour, protective factors, and how they fit together now. No high, moderate or low.", RISK, maxWords = 180, omit = false)
    private val planSection = s("plan", "Plan", "Each step of the plan as it was decided.", listOf(FactKind.PLAN), SectionForm.NUMBERED, 150, omit = false)
    private val medsSection = s("meds", "Medication", "Each medicine with dose, recent changes, adherence, benefit and side effects; relevant physical health.", listOf(FactKind.MEDICATIONS, FactKind.ALLERGIES, FactKind.PHYSICAL), maxWords = 150)

    fun builtIns(): List<NoteTemplate> = listOf(
        NoteTemplate("inpatient", "Inpatient review", builtIn = true, sections = listOf(
            s("present", "Present", "Who was present and the setting, in one sentence.", listOf(FactKind.CONTEXT), maxWords = 40),
            s("progress", "Progress since last review", "The course since the last review: what the patient reports, ward events and observations, and collateral.", listOf(FactKind.HISTORY, FactKind.SYMPTOMS, FactKind.WARD, FactKind.COLLATERAL, FactKind.SOCIAL, FactKind.SUBSTANCES), maxWords = 220),
            medsSection, mseSection, riskSection,
            s("impression", "Impression", "Tie the history, the mental state, the risk formulation and the plan together; justify the plan, the Mental Health Act status and the need for a locked ward when the facts support it.", listOf(FactKind.IMPRESSION, FactKind.LEGAL_STATUS), maxWords = 160, omit = false, all = true),
            planSection,
        )),
        NoteTemplate("followup", "Follow-up", builtIn = true, sections = listOf(
            s("present", "Present", "Who was present and the setting.", listOf(FactKind.CONTEXT), maxWords = 40),
            s("interval", "Interval history", "What has happened since the last appointment, symptoms, functioning, substance use.", listOf(FactKind.HISTORY, FactKind.SYMPTOMS, FactKind.SOCIAL, FactKind.SUBSTANCES, FactKind.COLLATERAL), maxWords = 220),
            medsSection, mseSection, riskSection,
            s("impression", "Impression", "A short synthesis of where things stand and why the plan follows.", listOf(FactKind.IMPRESSION), maxWords = 120, omit = false, all = true),
            planSection,
        )),
        NoteTemplate("telehealth", "Telehealth review", builtIn = true, sections = listOf(
            s("telehealth", "Telehealth", "Consent to a video consultation, identity confirmed, where the patient was, who else was present, and the quality of the connection.", listOf(FactKind.CONTEXT), maxWords = 60, omit = false),
            s("interval", "Interval history", "What has happened since the last appointment, symptoms, functioning, substance use.", listOf(FactKind.REASON, FactKind.HISTORY, FactKind.SYMPTOMS, FactKind.SOCIAL, FactKind.SUBSTANCES, FactKind.COLLATERAL), maxWords = 220),
            medsSection,
            s("mse", "Mental state examination (by video)", "As observed over video, in the usual order. Leave out any part with no facts.", MSE, maxWords = 160, omit = false),
            s("risk", "Risk and safety plan", "A qualitative risk formulation, and the safety plan and emergency contacts agreed for a patient seen remotely.", RISK, maxWords = 180, omit = false),
            s("impression", "Impression", "A short synthesis of where things stand and why the plan follows.", listOf(FactKind.IMPRESSION), maxWords = 120, omit = false, all = true),
            planSection,
        )),
        NoteTemplate("adhd", "ADHD assessment", builtIn = true, sections = listOf(
            s("present", "Context", "Who was present, referral source, and the setting or telehealth details.", listOf(FactKind.CONTEXT, FactKind.REASON), maxWords = 60),
            s("current", "Current symptoms and impact", "Inattention, hyperactivity and impulsivity now, with examples, and their impact on work, study, relationships and daily life.", listOf(FactKind.ADHD, FactKind.SYMPTOMS, FactKind.SOCIAL), maxWords = 220),
            s("childhood", "Childhood history", "Symptoms before age 12, school reports and history, and collateral from family.", listOf(FactKind.ADHD, FactKind.PERSONAL, FactKind.COLLATERAL), maxWords = 180),
            s("psych", "Psychiatric history and comorbidity", "Other mental health conditions, past and present, and how they bear on the ADHD question.", listOf(FactKind.PAST_PSYCH, FactKind.SYMPTOMS), maxWords = 150),
            s("substances", "Substance use", "Each substance, amount and pattern; misuse of stimulants.", listOf(FactKind.SUBSTANCES), maxWords = 100, omit = false),
            s("cardiac", "Physical and cardiac health", "Blood pressure, pulse, cardiac symptoms, personal and family cardiac history including sudden death, other conditions.", listOf(FactKind.PHYSICAL, FactKind.FAMILY), maxWords = 120, omit = false),
            s("meds", "Medication", "Current and past medicines including stimulants, doses, benefit, side effects; allergies.", listOf(FactKind.MEDICATIONS, FactKind.ALLERGIES), maxWords = 120),
            s("measures", "Rating scales", "Scales used and their scores.", listOf(FactKind.MEASURES), maxWords = 80),
            mseSection,
            s("risk", "Risk", "A qualitative risk formulation, including the risk of misuse or diversion of stimulants.", RISK + FactKind.SUBSTANCES, maxWords = 150, omit = false),
            s("impression", "Impression", "Whether the history meets DSM-5 criteria for ADHD, with the reasoning, differentials and comorbidity.", listOf(FactKind.IMPRESSION), maxWords = 160, omit = false, all = true),
            s("plan", "Plan", "Each step as decided, including prescribing authority or permit, monitoring of blood pressure, pulse and weight, and follow-up.", listOf(FactKind.PLAN), SectionForm.NUMBERED, 150, omit = false),
        )),
        NoteTemplate("intake", "Initial assessment", builtIn = true, sections = listOf(
            s("referral", "Referral and context", "Referral source and reason, who was present, setting.", listOf(FactKind.CONTEXT, FactKind.REASON), maxWords = 80),
            s("hpi", "History of presenting illness", "The presenting problem, its onset and course, current symptoms and triggers.", listOf(FactKind.HISTORY, FactKind.SYMPTOMS, FactKind.ADHD), maxWords = 250),
            s("pph", "Past psychiatric history", "Earlier illnesses, admissions, treatments and their effect, self-harm and suicide attempts.", listOf(FactKind.PAST_PSYCH), maxWords = 150, omit = false),
            s("medical", "Medical history", "Conditions, relevant investigations; allergies.", listOf(FactKind.PHYSICAL, FactKind.ALLERGIES), maxWords = 100, omit = false),
            s("meds", "Medication", "Current medicines with doses, adherence and side effects.", listOf(FactKind.MEDICATIONS), maxWords = 100, omit = false),
            s("substances", "Substance use", "Each substance, amount, pattern and consequences.", listOf(FactKind.SUBSTANCES), maxWords = 100, omit = false),
            s("family", "Family history", "Mental and physical illness in the family.", listOf(FactKind.FAMILY), maxWords = 80, omit = false),
            s("personal", "Personal and developmental history", "Birth and development, childhood, schooling, work, relationships.", listOf(FactKind.PERSONAL), maxWords = 150),
            s("social", "Social situation", "Housing, supports, finances, daily life.", listOf(FactKind.SOCIAL), maxWords = 100),
            s("forensic", "Forensic history", "Charges, convictions and police contact.", listOf(FactKind.FORENSIC), maxWords = 60, omit = false),
            s("collateral", "Collateral", "What others said.", listOf(FactKind.COLLATERAL), maxWords = 100),
            mseSection, riskSection,
            s("formulation", "Formulation", "A biopsychosocial formulation: predisposing, precipitating, perpetuating and protective factors.", listOf(FactKind.IMPRESSION), maxWords = 200, omit = false, all = true),
            s("diagnosis", "Diagnosis", "The diagnoses and differentials as stated.", listOf(FactKind.IMPRESSION), maxWords = 60),
            planSection,
        )),
    )

    /** An empty template to start from in the creator. */
    fun blank(id: String, name: String) = NoteTemplate(id, name, listOf(
        s("section1", "Notes", "What happened in the session.", FactKind.entries.toList(), maxWords = 300),
    ))
}
