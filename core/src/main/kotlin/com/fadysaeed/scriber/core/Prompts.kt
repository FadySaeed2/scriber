package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable

/**
 * A prompt copied above a transcript, so a large cloud model writes the document from it. Built-in
 * prompts can be changed and put back as they came; Fady can add his own.
 */
@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val text: String,
    /** Built into Scriber; can be changed, and reset to how it came. */
    val builtIn: Boolean = false,
    val updatedAtMillis: Long = 0,
)

object Prompts {
    /** The prompt each kind of session starts with, until Fady picks another. */
    val DEFAULTS: Map<SessionMode, String> = mapOf(
        SessionMode.CONVERSATION to "inpatient",
        SessionMode.MEETING to "minutes",
        SessionMode.DICTATION to "dictation",
    )

    /** A line on its own in a prompt: what follows it is copied after the transcript, as a reminder. */
    const val AFTER = "=== AFTER THE TRANSCRIPT ==="

    private val TRANSCRIPT = """
        About the transcript:
        - Speech recognition on a phone made it. Expect misheard words, broken sentences, and words given to the wrong speaker. A second, medical speech engine checked medical terms, but mistakes remain.
        - Speaker names, if there are any, are the ones the clinician gave; if a label looks wrong, go by what is said. If there are no names, work out from what is said who is the clinician, the patient and anyone else.
        - The line just above the transcript gives the date and length of the recording. Use it to check every date mentioned.
    """.trimIndent()

    private val CLINICAL = """
        You are writing clinical documentation for a consultant psychiatrist working in Australia and New Zealand, from the automatic transcript of a consultation below. Write the finished document in one reply, ready to paste into the electronic record. Do not ask questions, do not offer options, and write nothing before or after the document.

        {TRANSCRIPT}
        - The clinician often explains treatment in general terms: how medicines work, the options, side effects to watch for. That is information given, not the patient's history. Never record a medicine the clinician only explained as one the patient takes, has taken or will take.

        Hard rules:
        1. Use only what is in the transcript. Never invent findings, numbers, doses, dates, diagnoses, scores, history or reasoning.
        2. A question is not an answer. If a question got no clear answer ("okay", "perfect", silence, or unclear words), that topic was not covered.
        3. Write "denies", "no history of", "no known" or "nil" only when the patient or family was asked and clearly said no. The topics most often wrongly written as negative are: allergies; alcohol and drugs; chest pain, palpitations, shortness of breath and fainting; family history of heart disease or sudden death; weight change; current suicidal thoughts; whether diagnostic criteria are met.
        4. A clear yes or no counts even when the details are unclear: record the answer and say the details were unclear (for example "reported past self-harm; details unclear").
        5. Correct obvious mishearings without comment (medicine names, places, terms). Only where something that matters (a dose, a medicine, a legal status, a date) is genuinely unclear or impossible, write your best reading followed by [check: what was heard]. A handful at most.
        6. Do not state a dose or strength as certain when only the clinician assumed it and it was not confirmed.
        7. Never grade risk: do not put low, moderate, high, minimal or significant in front of "risk". Describe it instead.
        8. Gather the important topics that were not covered into one final line: "Not covered today: ...". Leave the line out if everything important was covered. Do not scatter "not assessed" or "not established" through the document.
        9. Write as the clinician, in the first person where the clinician acted (I reviewed, I explained, I recommended). Australian and New Zealand English spelling. Doses in digits with units (sertraline 50 mg mane).
        10. Plain text only: each heading on its own line, the plan numbered 1. 2. 3., no markdown, no asterisks, no hash signs, no bold, no em dashes.
        11. If a NOTE FROM THE CLINICIAN follows these instructions, follow it and treat its facts as true.
    """.trimIndent().replace("{TRANSCRIPT}", TRANSCRIPT)

    private val NOTE_STYLE = """
        Style:
        - Consultant level: cohesive clinical prose that synthesises, not a registrar's clerking list. Only the plan is a numbered list.
        - The headings below are a framework, not a form: merge, shorten or drop headings to fit the consultation. A short review gets a short note.
        - Risk is a qualitative formulation: static and dynamic factors, current thoughts and behaviour, protective factors and how they interact now.
        - Keep ward and service terms as said (for example 15/60 observations, escorted ground leave).
        - The impression ties the history, the mental state, the risk formulation and the plan together, and justifies the plan.
        - Usually 500 to 900 words; never more than 1000. Leave out what adds nothing.
    """.trimIndent()

    private val SHAPE = """
        The document looks like this, with the headings from the task:

        Heading
        A paragraph of prose.

        Plan
        1. First decision.
        2. Second decision.

        Not covered today: allergies, alcohol and drug use.
    """.trimIndent()

    private val CLINICAL_AFTER = """
        $AFTER
        Before you write, remember:
        - Only what the transcript says. A question without a clear answer means the topic was not covered.
        - No "denies", "no history of", "no known" or "nil" unless the question was asked and clearly answered no.
        - Medicines the clinician only explained are not the patient's.
        - Check every date against the recording date. Use [check: ...] only for what matters and is unclear.
        - Never grade risk as low, moderate or high.
        - Plain text, numbered plan, no asterisks or markdown. End with "Not covered today: ..." if anything important was not covered.
        Now write the document.
    """.trimIndent()

    private fun clinical(task: String) = "$CLINICAL\n\n$NOTE_STYLE\n\n${task.trimIndent()}\n\n$SHAPE\n\n$CLINICAL_AFTER"

    /** The prompt that goes above the transcript, and the reminder that goes after it. */
    fun split(text: String): Pair<String, String> {
        val i = text.lines().indexOfFirst { it.trim() == AFTER }
        if (i < 0) return text.trim() to ""
        val lines = text.lines()
        return lines.take(i).joinToString("\n").trim() to lines.drop(i + 1).joinToString("\n").trim()
    }

    fun builtIns(): List<PromptTemplate> = listOf(
        PromptTemplate("inpatient", "Inpatient review", clinical("""
            Task: write an inpatient ward review note with these headings.
            Present: who attended, in one line.
            Progress since last review: the course since the last review from the patient's account, nursing and ward reports (observation level, incidents, leave, meals, groups, engagement) and collateral from family or others.
            Medication: current medicines with doses, recent changes, adherence, benefit and side effects; relevant physical health, weight, bloods and other investigations.
            Mental state examination: only what was observed or reported, in the usual order (appearance and behaviour, speech, mood and affect, thought form and content, perception, cognition, insight and judgement). Leave out parts with nothing to go on.
            Risk: a qualitative formulation of risk to self, to others and from others, including command hallucinations, substance use, vulnerability and protective factors.
            Impression: ties it together; justifies the plan, the Mental Health Act status and the need for a locked ward when the transcript supports it.
            Plan: numbered; each decision with medicine and dose, leave, observations, investigations, referrals, who will do it and when.
        """), builtIn = true),
        PromptTemplate("followup", "Follow-up", clinical("""
            Task: write an outpatient follow-up note with these headings.
            Present: who attended and how (in person, video, phone).
            Interval history: what has happened since the last appointment, symptoms, functioning (work, study, relationships, daily life), stressors, substance use, collateral.
            Medication: current medicines with doses, changes, adherence, benefit and side effects; relevant physical health and monitoring.
            Mental state examination: what was observed or reported; leave out parts with nothing to go on.
            Risk: qualitative formulation.
            Impression: where things stand and why the plan follows.
            Plan: numbered, with doses, monitoring, who prescribes, and the next review.
        """), builtIn = true),
        PromptTemplate("telehealth", "Telehealth review", clinical("""
            Task: write a telehealth review note with these headings.
            Telehealth: consent to a video or phone consultation, identity confirmed, where the patient was, who else was present, and the quality of the connection, as far as the transcript shows; one line is enough.
            Interval history: what has happened since the last appointment, symptoms, functioning, substance use, collateral.
            Medication: current medicines with doses, changes, adherence, benefit and side effects.
            Mental state examination: what the consultation showed or was reported; leave out what a remote consultation could not show.
            Risk and safety plan: qualitative formulation, the safety plan, and emergency contacts for a patient seen remotely.
            Impression: where things stand and why the plan follows.
            Plan: numbered, with doses, prescriptions, monitoring and the next review.
        """), builtIn = true),
        PromptTemplate("adhd", "ADHD assessment", clinical("""
            Task: write an ADHD medication assessment with these headings.
            Context: setting (telehealth or in person), referral source, earlier assessment by a psychologist or others, and the purpose of today's appointment.
            Current symptoms and impact: inattention, hyperactivity and impulsivity now, with the patient's own examples, and their impact on work, study, relationships and daily life. Goals of treatment.
            Childhood history: symptoms before age 12, schooling, reports, collateral, or that an earlier assessment covered it.
            Psychiatric history and comorbidity: other conditions past and present, treatment, and how they bear on ADHD.
            Substance use: alcohol, cannabis and other drugs with amounts, caffeine and energy drinks, any misuse of stimulants.
            Medical and cardiac history: blood pressure history, palpitations, chest pain, syncope, other medical conditions; family cardiac history including sudden death, arrhythmia and early heart disease. Never write blood pressure or pulse values that were not stated.
            Family psychiatric history: who (be exact about the relationship, including half-siblings and the side of the family), what condition.
            Medication: the patient's current medicines with doses; stimulants tried before (medicine, brand or formulation, dose, how often, where they came from, for example a partner's prescription), benefit and side effects; allergies with the reaction.
            Discussion: the options the clinician explained and why one was preferred for this patient.
            Risk: qualitative formulation, including misuse or diversion of stimulants.
            Impression: whether the clinician concluded the criteria are met, in the clinician's terms. Do not name a diagnostic manual, differentials or comorbidity unless they were discussed.
            For a stimulant, the cardiac screen, alcohol and drug use, and allergies matter most: if any of them was not asked, it goes first in the "Not covered today" line.
            Plan: numbered; medicine, formulation, starting dose, titration and maximum; prescribing authority or permit and who will prescribe; monitoring of blood pressure, pulse and weight; advice given; follow-up and how to contact the clinician.
        """), builtIn = true),
        PromptTemplate("intake", "Initial assessment", clinical("""
            Task: write a full initial psychiatric assessment with these headings.
            Referral and context; History of presenting illness; Past psychiatric history (illnesses, admissions, treatments and their effect, self-harm and suicide attempts); Medical history and allergies; Medication; Substance use; Family history; Personal and developmental history; Social situation; Forensic history; Collateral; Mental state examination; Risk; Formulation (biopsychosocial: predisposing, precipitating, perpetuating and protective factors, in prose); Diagnosis and differentials (only as the clinician stated or clearly reasoned them); Plan (numbered).
            Put each heading on its own line. Leave out headings the interview did not cover, or say so in a few words.
        """), builtIn = true),
        PromptTemplate("gp_letter", "Letter to GP", clinical("""
            Task: write a letter to the patient's general practitioner after this consultation.
            Start "Dear Dr [name]" (use the GP's name if it was said, otherwise [GP name]). Thank them for the referral if there was one.
            Then: a short summary of the assessment and the history that matters; the clinician's impression; recommendations as a numbered list (medicine with dose, titration and maximum, monitoring, who prescribes and for how long, when to review, when to refer back); anything the GP should watch for.
            Warm, collegial and concise: about 400 to 600 words. End with "Kind regards" and [Name], Consultant Psychiatrist.
        """), builtIn = true),
        PromptTemplate("med_review", "Medication review", clinical("""
            Task: write a medication review or second opinion with these headings.
            Question asked: why the review was requested and by whom.
            Current regimen: each medicine with dose, timing, how long, adherence.
            Previous treatments: each trial on its own line: medicine, dose, duration, response, side effects, why stopped.
            Relevant history: diagnosis, physical health, investigations, substance use, pregnancy or contraception if discussed.
            Assessment: what is working, what is not, and why, including interactions and side effect burden.
            Recommendations: numbered, each with its rationale, dosing and titration, monitoring, and what to do if it fails.
        """), builtIn = true),
        PromptTemplate("discharge", "Discharge summary", clinical("""
            Task: write a discharge summary with these headings.
            Reason for admission; Legal status (during the admission and at discharge); Course in hospital (treatment, response, leave, incidents); Medication on discharge (each with dose, marking what was started, changed or stopped, and why); Mental state at discharge; Risk at discharge (qualitative formulation); Follow-up arrangements (who, when, where); Early warning signs and crisis plan.
            Keep to what the transcript covers.
        """), builtIn = true),
        PromptTemplate("family", "Family meeting", clinical("""
            Task: write a note of a family meeting with these headings.
            Present: each person and their relationship to the patient or role.
            Purpose of the meeting.
            Family's concerns and questions, in their words where it matters.
            Information shared by the team (diagnosis, treatment, legal status, discharge planning), noting what was shared with the patient's consent.
            Decisions and agreements.
            Actions: numbered, each with who will do it and by when.
        """), builtIn = true),
        PromptTemplate("mha_report", "Mental Health Act report", clinical("""
            Task: draft a report for a Mental Health Act review, tribunal or court hearing.
            Headings: Background (history in brief); Current presentation; Mental disorder or illness (the evidence for it as defined in the Act that applies); Risk or serious danger, or impaired capacity, as the applicable criteria require; Treatment and response; Less restrictive alternatives considered and why they are not enough now; The patient's views and those of family or carers; Recommendation.
            Name the Act and use its criteria only if the transcript says which jurisdiction applies; otherwise describe the evidence without citing an Act. Write it as a finished draft the clinician can sign after reading.
        """), builtIn = true),
        PromptTemplate("minutes", "Meeting minutes", """
            You are writing minutes of a meeting from an automatic transcript.

            {TRANSCRIPT}

            Rules:
            - Use only what was said. Never invent decisions, owners, dates or numbers.
            - Produce the finished minutes in one reply; do not ask questions.
            - Correct obvious misheard words without comment; only where a name, number or decision is genuinely unclear write your best reading followed by [check: what was heard].
            - Attribute decisions and actions to the person named in the transcript; if no one was named, write [owner to confirm].
            - Plain text, each heading on its own line, no markdown, no asterisks, no em dashes.
            - A question is not an answer; what was not settled goes under Open questions.

            Headings:
            Meeting: date (from the transcript header) and purpose.
            Attendees: the speakers and anyone named as present.
            Discussion: one short paragraph per topic, in the order discussed, with the main points and differing views.
            Decisions: numbered.
            Actions: numbered, each as "Owner: action, by when".
            Open questions: what was left unresolved.

            $AFTER
            Before you write, remember: only what was said; decisions and actions with the owner named in the transcript, or [owner to confirm]; plain text, numbered decisions and actions, no asterisks or markdown. Now write the minutes.
        """.trimIndent().replace("{TRANSCRIPT}", TRANSCRIPT), builtIn = true),
        PromptTemplate("dictation", "Dictation tidy", """
            Below is a dictation by a consultant psychiatrist, transcribed automatically by speech recognition on a phone. A second, medical speech engine checked the medicine names, but mistakes are still possible.

            Produce the final text exactly as dictated:
            - Fix punctuation, capitals and paragraphs. Turn spoken commands (full stop, period, comma, colon, new line, new paragraph) into punctuation and layout. Dictated headings stay as headings on their own line.
            - Correct obvious speech recognition errors without comment, especially medicine names, doses and medical terms. Only where a word that matters is genuinely unclear, write your best reading followed by [check: what was heard].
            - Produce the final text in one reply; do not ask questions.
            - Do not add, remove, summarise, reorder or reword anything else. Keep the speaker's own words and style.
            - Australian and New Zealand English spelling. Plain text, no markdown, no em dashes.

            $AFTER
            Before you write, remember: keep every dictated word; fix only punctuation, layout and clear mishearings; add nothing, summarise nothing; plain text. Now write the final text.
        """.trimIndent(), builtIn = true),
    )

    /** An empty prompt to start from, with the shared clinical rules already in place. */
    fun blank(id: String, name: String) = PromptTemplate(id, name, clinical("""
        Task: write the document with these headings (change them to what you need).
        Summary: what the consultation was about and what was decided.
        Plan: numbered.
    """))
}
