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

    private val TRANSCRIPT = """
        About the transcript below:
        - Speech recognition on a phone made it. Expect misheard words, broken sentences, and now and then words given to the wrong speaker. A second, medical speech engine checked the medicine names, but mistakes are still possible.
        - The speaker names are the ones the clinician gave. If a label looks wrong, go by what is said.
    """.trimIndent()

    private val CLINICAL = """
        You are helping a consultant psychiatrist working in Australia and New Zealand write clinical documentation from an automatic transcript.

        {TRANSCRIPT}
        - The clinician often explains treatment in general terms: how medicines work, the options, side effects to watch for. That is information given to the patient, not the patient's history or medication. Never record a medicine the clinician only explained as one the patient takes, has taken, or will take.

        Rules:
        - Use only what is in the transcript. Never invent findings, numbers, doses, dates, diagnoses, scores, history or reasoning.
        - Where a word is clearly misheard and the intended word is obvious from the context (a medicine name, a place, a term), correct it. Where you are not sure, write your best reading followed by [check: what the transcript says].
        - Do not write normal findings that were not observed or stated. If something the document needs was not covered, write [to complete] instead of guessing.
        - Keep who said what straight: what the patient reported, what family or staff reported, what the clinician observed, explained and decided.
        - Write as the clinician, in the first person where the clinician acted (I reviewed, I explained, I recommended).
        - Australian and New Zealand English spelling. Doses and numbers in digits with units (sertraline 50 mg mane).
        - Plain text, ready to paste into an electronic record: each heading on its own line, no markdown, no asterisks, no bullet symbols, no em dashes.
    """.trimIndent().replace("{TRANSCRIPT}", TRANSCRIPT)

    private val NOTE_STYLE = """
        Style:
        - Consultant level: cohesive clinical prose that synthesises, not a registrar's clerking list. Only the plan is a numbered list.
        - Risk is a qualitative formulation: static and dynamic factors, current thoughts and behaviour, protective factors and how they interact now. Never label risk as low, moderate or high.
        - Keep ward and service terms as said (for example 15/60 observations, escorted ground leave).
        - The impression ties the history, the mental state, the risk formulation and the plan together, and justifies the plan.
        - At most about 1000 words. Leave out what adds nothing.
    """.trimIndent()

    private fun clinical(task: String) = "$CLINICAL\n\n$NOTE_STYLE\n\n${task.trimIndent()}"

    fun builtIns(): List<PromptTemplate> = listOf(
        PromptTemplate("inpatient", "Inpatient review", clinical("""
            Task: write an inpatient ward review note with these headings.
            Present: who attended, in one line.
            Progress since last review: the course since the last review from the patient's account, nursing and ward reports (observation level, incidents, leave, meals, groups, engagement) and collateral from family or others.
            Medication: current medicines with doses, recent changes, adherence, benefit and side effects; relevant physical health, weight, bloods and other investigations.
            Mental state examination: only what was observed or reported, in the usual order (appearance and behaviour, speech, mood and affect, thought form and content, perception, cognition, insight and judgement). Mark parts with no information [to complete].
            Risk: a qualitative formulation of risk to self, to others and from others, including command hallucinations, substance use, vulnerability and protective factors.
            Impression: ties it together; justifies the plan, the Mental Health Act status and the need for a locked ward when the transcript supports it.
            Plan: numbered; each decision with medicine and dose, leave, observations, investigations, referrals, who will do it and when.
        """), builtIn = true),
        PromptTemplate("followup", "Follow-up", clinical("""
            Task: write an outpatient follow-up note with these headings.
            Present: who attended and how (in person, video, phone).
            Interval history: what has happened since the last appointment, symptoms, functioning (work, study, relationships, daily life), stressors, substance use, collateral.
            Medication: current medicines with doses, changes, adherence, benefit and side effects; relevant physical health and monitoring.
            Mental state examination: only what was observed or reported; mark parts with no information [to complete].
            Risk: qualitative formulation.
            Impression: where things stand and why the plan follows.
            Plan: numbered, with doses, monitoring, who prescribes, and the next review.
        """), builtIn = true),
        PromptTemplate("telehealth", "Telehealth review", clinical("""
            Task: write a telehealth review note with these headings.
            Telehealth: consent to a video or phone consultation, identity confirmed, where the patient was, who else was present, and the quality of the connection. Mark anything not stated [to complete].
            Interval history: what has happened since the last appointment, symptoms, functioning, substance use, collateral.
            Medication: current medicines with doses, changes, adherence, benefit and side effects.
            Mental state examination (by video): only what could be observed or was reported; mark parts with no information [to complete].
            Risk and safety plan: qualitative formulation, the safety plan, and emergency contacts for a patient seen remotely.
            Impression: where things stand and why the plan follows.
            Plan: numbered, with doses, prescriptions, monitoring and the next review.
        """), builtIn = true),
        PromptTemplate("adhd", "ADHD assessment", clinical("""
            Task: write an ADHD medication assessment with these headings.
            Context: setting (telehealth or in person), referral source, earlier assessment by a psychologist or others, and the purpose of today's appointment.
            Current symptoms and impact: inattention, hyperactivity and impulsivity now, with the patient's own examples, and their impact on work, study, relationships and daily life. Goals of treatment.
            Childhood history: symptoms before age 12, schooling, reports, collateral. Mark [to complete] if not covered.
            Psychiatric history and comorbidity: other conditions past and present, treatment, and how they bear on ADHD.
            Substance use: alcohol, cannabis and other drugs with amounts, caffeine and energy drinks, any misuse of stimulants.
            Medical and cardiac history: blood pressure history, palpitations, chest pain, syncope, other medical conditions; family cardiac history including sudden death, arrhythmia and early heart disease. Never write blood pressure or pulse values that were not stated.
            Family psychiatric history: who (be exact about the relationship, including half-siblings and the side of the family), what condition.
            Medication: the patient's current medicines with doses; stimulants tried before (medicine, brand or formulation, dose, how often, where they came from, for example a partner's prescription), benefit and side effects; allergies with the reaction.
            Discussion: the options the clinician explained and why one was preferred for this patient.
            Risk: qualitative formulation, including misuse or diversion of stimulants.
            Impression: whether the clinician concluded the criteria are met, in the clinician's terms. Do not name a diagnostic manual, differentials or comorbidity unless they were discussed.
            Plan: numbered; medicine, formulation, starting dose, titration and maximum; prescribing authority or permit and who will prescribe; monitoring of blood pressure, pulse and weight; advice given; follow-up and how to contact the clinician.
        """), builtIn = true),
        PromptTemplate("intake", "Initial assessment", clinical("""
            Task: write a full initial psychiatric assessment with these headings.
            Referral and context; History of presenting illness; Past psychiatric history (illnesses, admissions, treatments and their effect, self-harm and suicide attempts); Medical history and allergies; Medication; Substance use; Family history; Personal and developmental history; Social situation; Forensic history; Collateral; Mental state examination; Risk; Formulation (biopsychosocial: predisposing, precipitating, perpetuating and protective factors, in prose); Diagnosis and differentials (only as the clinician stated or clearly reasoned them); Plan (numbered).
            Put each heading on its own line. Mark headings the interview did not cover [to complete].
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
            Mark anything the transcript does not cover [to complete].
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
            Headings: Background (identifying details as [to complete], history in brief); Current presentation; Mental disorder or illness (the evidence for it as defined in the Act that applies); Risk or serious danger, or impaired capacity, as the applicable criteria require; Treatment and response; Less restrictive alternatives considered and why they are not enough now; The patient's views and those of family or carers; Recommendation.
            Name the Act and use its criteria only if the transcript says which jurisdiction applies; otherwise write [jurisdiction to complete] and keep to the evidence. This is a draft for the clinician to check, not a final legal document.
        """), builtIn = true),
        PromptTemplate("minutes", "Meeting minutes", """
            You are writing minutes of a meeting from an automatic transcript.

            {TRANSCRIPT}

            Rules:
            - Use only what was said. Never invent decisions, owners, dates or numbers.
            - Correct obvious misheard words from the context; where unsure write your best reading followed by [check: what the transcript says].
            - Attribute decisions and actions to the person named in the transcript; if no one was named, write [owner to confirm].
            - Plain text, each heading on its own line, no markdown, no asterisks, no em dashes.

            Headings:
            Meeting: date (from the transcript header) and purpose.
            Attendees: the speakers and anyone named as present.
            Discussion: one short paragraph per topic, in the order discussed, with the main points and differing views.
            Decisions: numbered.
            Actions: numbered, each as "Owner: action, by when".
            Open questions: what was left unresolved.
        """.trimIndent().replace("{TRANSCRIPT}", TRANSCRIPT), builtIn = true),
        PromptTemplate("dictation", "Dictation tidy", """
            Below is a dictation by a consultant psychiatrist, transcribed automatically by speech recognition on a phone. A second, medical speech engine checked the medicine names, but mistakes are still possible.

            Produce the final text exactly as dictated:
            - Fix punctuation, capitals and paragraphs. Turn spoken commands (full stop, period, comma, colon, new line, new paragraph) into punctuation and layout. Dictated headings stay as headings on their own line.
            - Correct obvious speech recognition errors, especially medicine names, doses and medical terms. Where unsure, write your best reading followed by [check: what the transcript says].
            - Do not add, remove, summarise, reorder or reword anything else. Keep the speaker's own words and style.
            - Australian and New Zealand English spelling. Plain text, no markdown, no em dashes.
        """.trimIndent(), builtIn = true),
    )

    /** An empty prompt to start from, with the shared clinical rules already in place. */
    fun blank(id: String, name: String) = PromptTemplate(id, name, clinical("""
        Task: write [the document] with these headings.
        [Heading]: [what it should hold]
    """))
}
