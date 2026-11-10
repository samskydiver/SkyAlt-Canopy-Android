package com.skyalt.canopy.model

/** The kind of jumping a Performance Designs series is made for, shown as a badge on its tile and its page. */
enum class CanopyModelFamily(val titleKey: String) {
    MAIN("Main canopy"),
    HIGH_PERFORMANCE("High performance"),
    CRW("CRW"),
    ACCURACY("Accuracy"),
    BASE("BASE"),
    RESERVE("Reserve"),
}

/**
 * One Performance Designs series on sale today: the sizes PD makes, the
 * bundled 3D model and preview, the texts of its page and the flight
 * model's coefficients for it. The powered canopies and the discontinued
 * series are left out. The coefficients are estimates from PD's own
 * descriptions, not measurements. Port of CanopyModelCatalog.swift.
 */
data class CanopyModel(
    val key: String,
    /** The series' name with two letters hidden, "S*br*3": the names are the maker's, so none is shown in full. */
    val name: String,
    val family: CanopyModelFamily,
    val cells: Int,
    val isCrossBraced: Boolean,
    /** The family of the flight model's rules: a student canopy never stalls, the rest do. */
    val type: CanopyType,
    /** Square feet, as PD sells them, smallest first. */
    val sizes: List<Double>,
    /** The size the bundled 3D model and preview were built at. */
    val modelSize: Double,
    /** "PD_Sabre3_150": the bundled .glb and _above.jpg, without the extension. */
    val resourceStem: String,
    /** Catalog keys (the English texts) of the one-line positioning and the description. */
    val leadKey: String,
    val descriptionKey: String,
    val coefficients: CanopyTypeCoefficients,
) {
    /** The bundled 3D model in the app's assets: metres, y up, flying towards −z, the origin at the harness. */
    val modelAsset get() = "pdcanopies/$resourceStem.glb"

    /** The bundled picture of the model from the front and above. */
    val previewAsset get() = "pdcanopies/${resourceStem}_above.jpg"

    val isForStudents get() = key in STUDENT_KEYS

    /** The size nearest to [area], the larger one on a tie. */
    fun nearestSize(area: Double): Double {
        var best = sizes.firstOrNull() ?: return modelSize
        for (size in sizes) {
            val d = kotlin.math.abs(size - area)
            val db = kotlin.math.abs(best - area)
            if (d < db || (d == db && size > best)) best = size
        }
        return best
    }

    companion object {
        /** The series the setup starts on, and the one a saved student setup from before the catalogue becomes. */
        const val STUDENT_KEY = "navigator"

        /** The series PD offers to students, badged as such on the page. */
        val STUDENT_KEYS = setOf("navigator", "silhouette")

        /** The page texts with every series name hidden as on the tiles and the maker's initials as "P*". */
        fun masked(text: String): String {
            var result = text
            for ((full, hidden) in MASKED_NAMES) result = result.replace(full, hidden)
            return PD_INITIALS.replace(result, "P*")
        }

        private val PD_INITIALS = Regex("\\bPD\\b")

        /** Longest first, so "CF Storm" goes before "Storm". */
        private val MASKED_NAMES = listOf(
            "Comp Velocity" to "C*mp V*locity",
            "Silhouette" to "Silhou*tt*",
            "PD Reserve" to "P* Res*rve",
            "Navigator" to "Na*i*ator",
            "Peregrine" to "Pe*egr*ne",
            "Lightning" to "L*gh*ning",
            "Stiletto" to "S*ilett*",
            "Valkyrie" to "V*l*yrie",
            "CF Storm" to "C* S*orm",
            "Spectre" to "Spe*t*e",
            "Horizon" to "Ho*izo*",
            "Optimum" to "O*ti*um",
            "Sabre3" to "S*br*3",
            "Katana" to "K*t*na",
            "Pulse" to "P*ls*",
            "Storm" to "S*or*",
            "Proxy" to "Pr*x*",
            "Zero" to "Z*r*",
        )

        /**
         * The series a computer-flown canopy of the type flies: the student
         * canopy, an everyday 7-cell, or a high-performance wing for the
         * swoopers, picked at random among them.
         */
        fun traffic(type: CanopyType, random: kotlin.random.Random = kotlin.random.Random): CanopyModel {
            val keys = when (type) {
                CanopyType.STUDENT -> listOf(STUDENT_KEY)
                CanopyType.SEVEN_CELL -> listOf("spectre", "storm", "horizon")
                CanopyType.PERFORMANCE -> listOf("stiletto", "katana")
                CanopyType.CROSS_BRACED -> listOf("compvelocity", "valkyrie", "peregrine")
            }
            return forKey(keys[random.nextInt(keys.size)]) ?: fallback(type)
        }

        fun forKey(key: String?): CanopyModel? = ALL.firstOrNull { it.key == key }

        /** The series a setup from before the catalogue stands for. */
        fun fallback(type: CanopyType): CanopyModel {
            val key = when (type) {
                CanopyType.STUDENT -> STUDENT_KEY
                CanopyType.SEVEN_CELL -> "spectre"
                CanopyType.PERFORMANCE -> "sabre3"
                CanopyType.CROSS_BRACED -> "valkyrie"
            }
            return forKey(key) ?: ALL[0]
        }

        /** Every series, easiest first: the sport mains, the high-performance wings, then the CRW, accuracy, BASE and reserve canopies. */
        val ALL: List<CanopyModel> = listOf(
        CanopyModel(
            "navigator", "Na*i*ator", CanopyModelFamily.MAIN, 9, false, CanopyType.STUDENT,
            listOf(200.0, 220.0, 240.0, 260.0, 280.0, 300.0), 240.0, "PD_Navigator_240",
            "The student canopy: forgiving and dependable",
            "A 9-cell student canopy built to forgive the mistakes of a first season: a poor body position on opening, a flare started early or late. Openings are reliable, the controls are gentle and the landings are soft. Hybrid ZP and low-porosity fabric keeps it durable and easy to pack.",
            CanopyTypeCoefficients(20.0, 2.5, 2.52, 0.85, 45.0, 1.3, 2.4),
        ),
        CanopyModel(
            "silhouette", "Silhou*tt*", CanopyModelFamily.MAIN, 9, false, CanopyType.PERFORMANCE,
            listOf(135.0, 150.0, 170.0, 190.0, 210.0, 230.0, 260.0), 190.0, "PD_Silhouette_190",
            "Gentle and easy, from student to experienced",
            "A lightly tapered 9-cell for jumpers who want a calm, predictable canopy from their first solo jumps through many seasons. Soft, short snivels and on-heading openings; a flat glide and a short recovery arc; responsive even at light wing loadings, with a flare that is easy to time and has power in reserve. The hybrid fabric packs small.",
            CanopyTypeCoefficients(20.5, 2.6, 2.52, 0.95, 55.0, 1.0, 2.3),
        ),
        CanopyModel(
            "pulse", "P*ls*", CanopyModelFamily.MAIN, 9, false, CanopyType.PERFORMANCE,
            listOf(107.0, 120.0, 135.0, 150.0, 170.0, 190.0, 210.0, 230.0, 260.0), 150.0, "PD_Pulse_150",
            "A very flat glide in a low-bulk 9-cell",
            "A 9-cell with a very flat glide, for novice to experienced jumpers, wingsuit pilots and anyone who ends up a long way from the landing area. Soft, short snivels with no off-heading tendency, a short recovery arc and a flare that is easy to time. Its ZP top skin and low-bulk bottom skin pack about one size smaller than an all-ZP canopy.",
            CanopyTypeCoefficients(20.5, 2.8, 2.59, 0.95, 55.0, 0.95, 2.2),
        ),
        CanopyModel(
            "spectre", "Spe*t*e", CanopyModelFamily.MAIN, 7, false, CanopyType.SEVEN_CELL,
            listOf(97.0, 107.0, 120.0, 135.0, 150.0, 170.0, 190.0, 210.0, 230.0), 150.0, "PD_Spectre_150",
            "Soft openings and a surprisingly flat glide",
            "The classic 7-cell: soft, long snivels that stay on heading, a surprisingly flat glide for a 7-cell and a short recovery arc. Control inputs are smooth and progressive, which is why camera flyers, wingsuit pilots and demo jumpers trust it, and why many fly it at higher wing loadings too.",
            CanopyTypeCoefficients(20.0, 2.7, 2.14, 0.9, 55.0, 1.0, 2.1),
        ),
        CanopyModel(
            "sabre3", "S*br*3", CanopyModelFamily.MAIN, 9, false, CanopyType.PERFORMANCE,
            listOf(89.0, 97.0, 107.0, 120.0, 135.0, 150.0, 170.0, 190.0, 210.0, 230.0), 150.0, "PD_Sabre3_150",
            "The all-rounder with a long recovery arc",
            "PD's all-round 9-cell, with tail ribs and a redesigned, more refined opening. A steeper glide and a long recovery arc give it real performance: progressive, controllable front risers, strong rear risers and a powerful bottom-end flare. Inputs answer immediately, crisply and predictably. Novices fly it at moderate loadings; advanced jumpers load it up to learn high-performance flying.",
            CanopyTypeCoefficients(22.0, 2.5, 2.59, 1.0, 65.0, 0.8, 2.3),
        ),
        CanopyModel(
            "storm", "S*or*", CanopyModelFamily.MAIN, 7, false, CanopyType.SEVEN_CELL,
            listOf(97.0, 107.0, 120.0, 135.0, 150.0, 170.0, 190.0, 210.0, 230.0), 150.0, "PD_Storm_150",
            "A 9-cell's feel in a 7-cell",
            "A 7-cell that packs the glide, recovery arc and flare of a 9-cell into a 7-cell's soft, long snivel. The control range is long and the bottom-end flare powerful; the glide is steeper than the Spectre's. Novices fly it lightly loaded, advanced jumpers load it up, and it is at home under a wingsuit or in casual CRW.",
            CanopyTypeCoefficients(21.5, 2.45, 2.14, 0.95, 60.0, 0.85, 2.3),
        ),
        CanopyModel(
            "horizon", "Ho*izo*", CanopyModelFamily.MAIN, 7, false, CanopyType.SEVEN_CELL,
            listOf(120.0, 135.0, 150.0, 170.0, 190.0), 150.0, "PD_Horizon_150",
            "Built for wingsuits, with the strongest flare in its class",
            "A 7-cell made for wingsuit flying: soft, dependable openings even behind a large suit, lively handling and what PD calls the strongest flare in its class. Low-bulk fabric lets it go two sizes larger in the same container than an ordinary ZP canopy.",
            CanopyTypeCoefficients(19.5, 2.5, 2.1, 0.95, 55.0, 0.9, 2.5),
        ),
        CanopyModel(
            "stiletto", "S*ilett*", CanopyModelFamily.HIGH_PERFORMANCE, 9, false, CanopyType.PERFORMANCE,
            listOf(89.0, 97.0, 107.0, 120.0, 135.0, 150.0, 170.0, 190.0), 135.0, "PD_Stiletto_135",
            "Fully elliptical, with snappy turns",
            "The fully elliptical 9-cell that defined the high-performance canopy: snappy turns, toggles that answer the lightest touch, a flat glide and a short recovery arc. For intermediate to experienced jumpers who want a playful, responsive wing and the glide to get home from a long spot.",
            CanopyTypeCoefficients(22.5, 2.7, 2.68, 1.15, 70.0, 0.6, 2.0),
        ),
        CanopyModel(
            "katana", "K*t*na", CanopyModelFamily.HIGH_PERFORMANCE, 9, false, CanopyType.PERFORMANCE,
            listOf(83.0, 89.0, 97.0, 107.0, 120.0, 135.0, 150.0, 170.0), 120.0, "PD_Katana_120",
            "The last step before a cross-braced wing",
            "A highly tapered elliptical 9-cell for advanced and expert jumpers moving on from a Sabre3 who are not ready for a cross-braced wing. Soft, smooth, long snivels; a very steep glide and a long recovery arc; a long control range with a wide speed range, very light front riser pressure and a long, powerful flare.",
            CanopyTypeCoefficients(23.5, 2.4, 2.74, 1.1, 72.0, 0.65, 2.2),
        ),
        CanopyModel(
            "compvelocity", "C*mp V*locity", CanopyModelFamily.HIGH_PERFORMANCE, 7, true, CanopyType.CROSS_BRACED,
            listOf(71.0, 75.0, 79.0, 84.0, 90.0, 96.0, 103.0, 111.0), 90.0, "PD_CompVelocity_90",
            "The first cross-braced canopy, and a competition wing",
            "PD's recommended first cross-braced canopy: 7 cells split into 21 chambers for a stiffer, faster wing. Very good openings, a very steep glide, a long recovery arc, lively, powerful handling and a very strong shutdown. For experienced canopy pilots learning high-performance flying under coaching, and for competition.",
            CanopyTypeCoefficients(25.0, 2.5, 2.68, 1.1, 80.0, 0.6, 1.9),
        ),
        CanopyModel(
            "valkyrie", "V*l*yrie", CanopyModelFamily.HIGH_PERFORMANCE, 7, true, CanopyType.CROSS_BRACED,
            listOf(67.0, 71.0, 75.0, 79.0, 84.0, 90.0, 96.0, 103.0, 111.0), 84.0, "PD_Valkyrie_84",
            "An expert's cross-braced wing with inflatable tips",
            "A cross-braced 7-cell with inflatable wing tips and tail ribs, for experts who already master cross-braced canopies; it must not be a first one. Extremely sensitive to every input, with strong rear risers and a very strong shutdown; smooth openings, terminal or sub-terminal. The Hybrid version's sail-fabric ribs add power, response and swoop distance.",
            CanopyTypeCoefficients(25.5, 2.6, 2.84, 1.2, 82.0, 0.55, 1.9),
        ),
        CanopyModel(
            "peregrine", "Pe*egr*ne", CanopyModelFamily.HIGH_PERFORMANCE, 9, true, CanopyType.CROSS_BRACED,
            listOf(61.0, 64.0, 67.0, 71.0, 75.0, 79.0, 84.0), 71.0, "PD_Peregrine_71",
            "The competition wing: sub-terminal openings only",
            "PD's top competition canopy: cross-braced with a Schuemann planform and inflatable wing tips, for elite canopy pilots who have mastered the Valkyrie, and for XRW. A very steep glide and a very long recovery arc, built for speed and distance. Sub-terminal openings only.",
            CanopyTypeCoefficients(26.5, 2.4, 3.0, 1.25, 85.0, 0.5, 1.8),
        ),
        CanopyModel(
            "cfstorm", "C* S*orm", CanopyModelFamily.CRW, 7, false, CanopyType.SEVEN_CELL,
            listOf(97.0, 107.0, 120.0, 135.0, 150.0, 170.0, 190.0, 210.0, 230.0), 150.0, "PD_CFStorm_150",
            "The competition CRW canopy",
            "The Storm built for competition and demonstration CRW, for experienced Storm pilots: slightly more performance and speed than the Lightning, a wide speed range, solid handling and a powerful flare. Dacron lines, red continuous A lines, target panels on the top skin, reinforced stabilizers and a mesh slider for fast sub-terminal openings.",
            CanopyTypeCoefficients(21.5, 2.4, 2.14, 0.95, 60.0, 0.85, 2.3),
        ),
        CanopyModel(
            "lightning", "L*gh*ning", CanopyModelFamily.CRW, 7, false, CanopyType.SEVEN_CELL,
            listOf(113.0, 126.0, 143.0, 160.0, 176.0, 193.0, 218.0, 253.0), 160.0, "PD_Lightning_160",
            "The CRW canopy for formations, stacks and demos",
            "The 7-cell CRW canopy for large and small formations, entry and intermediate competition and demonstration jumps with flags and stacked canopies. Consistent across its sizes, with on-heading openings, a reinforced leading edge, 600 Dacron lines and a retractable bridle. Not meant for terminal openings.",
            CanopyTypeCoefficients(20.0, 2.3, 2.1, 0.9, 50.0, 1.0, 2.1),
        ),
        CanopyModel(
            "zero", "Z*r*", CanopyModelFamily.ACCURACY, 7, false, CanopyType.SEVEN_CELL,
            listOf(205.0, 215.0, 225.0, 235.0, 245.0, 255.0, 265.0, 275.0, 285.0, 295.0, 305.0, 315.0, 325.0), 255.0, "PD_Zero_255",
            "The accuracy canopy with a controllable descent",
            "A 7-cell accuracy canopy of low aspect ratio, designed for precision landings: lively handling and a controllable, steep descent in deep brakes. Soft cell openings, C/D vents at the tips, adjustable steering geometry and a choice of hard or soft toggles. For experienced accuracy competitors and demo jumps.",
            CanopyTypeCoefficients(18.0, 2.1, 1.74, 0.9, 45.0, 1.1, 2.4),
        ),
        CanopyModel(
            "proxy", "Pr*x*", CanopyModelFamily.BASE, 7, false, CanopyType.SEVEN_CELL,
            listOf(200.0, 220.0, 240.0, 260.0, 280.0), 240.0, "PD_Proxy_240",
            "The wingsuit BASE canopy with an excellent glide",
            "A 7-cell BASE canopy, slider up or down, for novice and experienced wingsuit BASE jumpers: good opening headings, a glide PD calls excellent and a powerful flare. Low-bulk fabric makes it more than 30 percent smaller and about 20 percent lighter than a conventional BASE canopy. Large-hole mesh slider, optional PAC vents.",
            CanopyTypeCoefficients(19.5, 2.7, 2.05, 0.9, 50.0, 1.0, 2.3),
        ),
        CanopyModel(
            "pdreserve", "P* Res*rve", CanopyModelFamily.RESERVE, 7, false, CanopyType.SEVEN_CELL,
            listOf(99.0, 106.0, 113.0, 126.0, 143.0, 160.0, 176.0, 193.0, 218.0, 235.0, 253.0, 281.0), 176.0, "PD_PDReserve_176",
            "The reserve with a record of over 29 years",
            "The F-111-type 7-cell reserve with a service record of more than 29 years. Lively yet predictable handling, a good glide in brakes and excellent landings, with a better flare and opening than reserves before it. Tested well beyond its TSO weight and speed limits.",
            CanopyTypeCoefficients(19.0, 2.3, 2.1, 0.85, 45.0, 1.1, 2.1),
        ),
        CanopyModel(
            "optimum", "O*ti*um", CanopyModelFamily.RESERVE, 7, false, CanopyType.SEVEN_CELL,
            listOf(99.0, 106.0, 113.0, 126.0, 143.0, 160.0, 176.0, 193.0, 218.0, 235.0, 253.0), 160.0, "PD_Optimum_160",
            "A low-bulk reserve that flies like a main",
            "A 7-cell reserve in PD's 30-denier low-bulk, low-permeability fabric, for anyone who wants a small, docile reserve that is easy to flare. It flies and lands more like a main, with a flare and shutdown far beyond other reserves and more forgiving landings; terminal openings are prompt and smooth with lower opening forces.",
            CanopyTypeCoefficients(19.5, 2.4, 2.1, 0.9, 50.0, 1.0, 2.3),
        ),
        )
    }
}
