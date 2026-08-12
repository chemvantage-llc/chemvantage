# ChemVantage Question JSON Ingest Guide

ChemVantage supports a batch question contribution flow in the **Contribute** servlet. The batch form accepts a pasted JSON array in a textarea and converts each element into a proposed question item.

This guide explains how to build a JSON payload that the batch importer can read successfully. The guide can be uploaded to an AI model
to assist you in converting your existing text file to the JSON array format required by ChemVantage.

## Important behavior

- The importer expects a **top-level JSON array**.
- Each element of the array must be a **JSON object** representing one proposed question.
- The batch form is **paste-based**, not a file-upload control. You paste JSON into the textarea and submit it.
- The JSON object is deserialized directly into a Question entity, so field names must match the Java model fields.

## Minimum structure

At minimum, each question object should include:

- `type` - the question type name
- `text` - the question prompt
- `correctAnswer` - the answer string or value (not required for ESSAY questions)

You should also include the fields needed for the selected question type, especially `choices` for multiple-choice style questions and `tag` or parameter fields for fill-in/numeric questions.

## Supported question types

The batch importer accepts the same type names used by the `Question` model:

- `MULTIPLE_CHOICE`
- `TRUE_FALSE`
- `SELECT_MULTIPLE`
- `FILL_IN_WORD`
- `NUMERIC`
- `ESSAY`

## Field reference

### Required or recommended fields

#### `type`

Use one of the supported uppercase type names listed above.

Examples:

```json
"type": "MULTIPLE_CHOICE"
```

```json
"type": "NUMERIC"
```

#### 'concept'

Optional - must match exactly one of the allowed values listed at the end of this file. 
If the concept field is not included in the JSON object, ChemVantage will use an AI model to try to assign it to one of the allowed values.

Example:

```json
"concept": "Scientific Method"
```

#### `text`

The visible question prompt. HTML markup may be used for special characters like &rarr; or &Psi;, and some questions use simple markup like `<br/>` or subscript tags. May also contain hash tag delimited expressions (see parameterString).

Example:

```json
"text": "Which two of the following compounds have a permanent dipole moment?"
```

#### `correctAnswer`

The answer format depends on the type:

- MULTIPLE_CHOICE: a single letter string such as `a`
- SELECT_MULTIPLE: a string of letters such as `be` or `acd`
- TRUE_FALSE: boolean true or false
- FILL_IN_WORD: a comma-separated list of acceptable answers
- NUMERIC: a string representing a numeric value or mathematical expression (see parameterString). Use scientific E notation (e.g., "6.275E-9")
- ESSAY: not required because these are scored by an AI model
Examples:

```json
"correctAnswer": "acd"
```

```json
"correctAnswer": "molecular weight, molar mass"
```

```json
"correctAnswer": "#-log10(b/1000*1E-5)#"
```

### Type-specific fields

#### `choices`

Use for `MULTIPLE_CHOICE` and `SELECT_MULTIPLE` questions.

- Must be a JSON array of strings
- Keep the number of choices within the app's normal limit of five
- The order of the answers is the order the learner will see unless scrambled later

Example:

```json
"choices": [
  "BF<sub>3</sub>",
  "H<sub>2</sub>O",
  "Cl<sub>2</sub>",
  "CO<sub>2</sub>",
  "NH<sub>3</sub>"
]
```

#### 'nChoices'

Required for `MULTIPLE_CHOICE` and `SELECT_MULTIPLE` questions only.

- Integer number of choices (maximum of 5)

Example:

```json
"nChoices": 4
```

#### `tag`

Use for fill-in-word and numeric questions when the question text needs a trailing unit or completion phrase.

Examples:

```json
"tag": "kg"
```

```json
"tag": " of any particle."
```

#### `requiredPrecision`

Use for numeric questions. This is the allowed percent error, stored as a number. Use 0 to require the exact value (e.g., integer).

Example:

```json
"requiredPrecision": 2.0
```

#### `significantFigures`

Use for numeric questions when the answer should be rounded to a particular precision. Use 0 to disable sig fig evaluation (e.g. for an exact value or for irrational or repeating decimal numbers).

Example:

```json
"significantFigures": 3
```

#### `parameterString`

Use when the question text contains variable expression placeholders such as `#a#` when the question needs randomized parameters.
For example, the string "What is the mass of #b/100# moles of C<sub>#a#</sub>H<sub>#2*a+2#</sub>?" might be parsed as
"What is the mass of 0.42 moles of C<sub>3</sub>H<sub>8</sub>?" and have a correctAnswer string "#b/100*(a*12.011+(2*a+2)*1.008)#"
and tag "g". Question items may contain up to four randomized integer variables (a-d). The letters e and p are reserved for the 
constants e and pi. Functions such as ln() log10(), exp(), sqrt(), floor() are legal.

The expected format is a space-delimited list of variable integer ranges, for example:

```json
"parameterString": "a 2:5 b 31:49 c 800:990 d 71:99"
```

#### `hint`

Optional hint text. May contain hash tag delimited expressions (see parameterString).

Example:

```json
"hint": "Think about polarity. A molecule with a net dipole is not symmetrical."
```

#### `scrambleChoices`

Optional boolean for choice randomization for MULTIPLE_CHOICE and SELECT_MULTIPLE items. The default value is false.

Example:

```json
"scrambleChoices": true
```

#### `strictSpelling`

Optional boolean for FILL_IN_WORD matching. Default value is false (forgives minor spelling errors). In either case, punctuation and capitalization are ignored.

Example:

```json
"strictSpelling": true
```

## Recommended authoring rules

1. Use valid JSON only. Do not include comments, trailing commas, or single-quoted strings.
2. Keep the top level as an array, even if you are importing only one question.
3. Make sure every `concept` matches a real concept title in ChemVantage exactly, including spacing and capitalization.
4. Use the canonical question type names in uppercase.
5. Provide `choices` for choice-based questions and make sure the correct answer refers to the choice letters in the same order.
6. For numeric items, include both `requiredPrecision` and `significantFigures` when the desired grading behavior depends on rounding.
7. For parameterized items, verify that the placeholders in the text match the variables described in `parameterString`.
8. Keep HTML in question text simple and consistent with the rest of the question bank.
9. Test the JSON with a parser before pasting it into ChemVantage.

## Example payloads

### MULTIPLE_CHOICE example

```json
[
  {
    "type": "MULTIPLE_CHOICE",
    "text": "A substance that adopts part of the shape of its container but not its full volume is in the",
    "choices": [
      "liquid phase.",
      "solid phase.",
      "gas phase.",
      "none of the above."
    ],
    "nChoices": 4,
    "correctAnswer": "a",
    "scrambleChoices": false,
    "hint": "Think about the properties of liquids.",
    "solution": "Liquids take the shape of their container but keep a fixed volume."
  }
]
```

### SELECT_MULTIPLE example

```json
[
  {
    "type": "SELECT_MULTIPLE",
    "text": "Which two of the following compounds have a permanent dipole moment?",
    "choices": [
      "BF<sub>3</sub>",
      "H<sub>2</sub>O",
      "Cl<sub>2</sub>",
      "CO<sub>2</sub>",
      "NH<sub>3</sub>"
    ],
    "correctAnswer": "be",
    "scrambleChoices": true
  }
]
```

### TRUE_FALSE example

```json
[
  {
    "type": "TRUE_FALSE",
    "text": "Every molecule that contains polar covalent bonds is a polar molecule.",
    "correctAnswer": false
  }
]
```

### FILL_IN_WORD example

```json
[
  {
    "type": "FILL_IN_WORD",
    "text": "According to VSEPR theory, a molecule with a central atom surrounded by three bonding pairs and one lone pair has a ",
    "correctAnswer": "trigonal pyramidal",
    "tag": "molecular geometry."
  }
]
```

### NUMERIC example

```json
[
  {
    "type": "NUMERIC",
    "text": "Evaluate the expression<br/> #a# x + (#b#/x) + log10(#c# x)<br/> when the value of x is #d/10#",
    "correctAnswer": "a*d/10+b/(d/10)+log10(c*d/10)",
    "requiredPrecision": 1.0,
    "significantFigures": 0,
    "parameterString": "a 2:5 b 31:49 c 800:990 d 71:99",
    "tag": ""
  }
]
```

### ESSAY example

```json
[
  {
    "type": "ESSAY",
    "text": "Explain the difference between accuracy and precision."
  }
]
```

## Common mistakes

- Sending a single JSON object instead of an array.
- Typing the question type in lowercase or with spaces.
- Forgetting `choices` for multiple-choice questions.
- Adding trailing commas, which makes the JSON invalid.
- Putting smart quotes into the payload instead of straight quotes.

## Where this is used in the app

The batch form is exposed from the Contribute servlet and parsed in the batch upload handler. The app stores the submitted objects as custom Question entities that may be reviewed and edited before including them in a Homework or Quiz assignment.

## Allowed values of concept

- Scientific Method
- Atoms & Molecules
- Phases & Classification of Matter
- Physical & Chemical Properties
- Units, Prefixes & Conversions
- Density
- Uncertainty, Accuracy & Precision
- Significant Figures
- Dimensional Analysis
- Temperature Conversions
- Dalton's Atomic Theory
- Discovery of the Electron
- Rutherford Scattering
- Atomic Structure
- Atomic Symbols
- Isotopes and Average Mass
- Periodic Table of Elements
- Ionic and Covalent Compounds
- Chemical Nomenclature
- The Mole & Avogadro's Number
- Mass, Moles and Particles
- Formula Mass
- Empirical & Molecular Formulas
- Molarity
- Molality, PPM, PPB
- Total & Net Ionic Equations
- Balancing Chemical Equations
- Classifying Chemical Equations
- Reaction Stoichiometry
- Reaction Yields
- Quantitative Analysis
- Heat and Work
- Calorimetry
- Enthalpy
- First Law of Thermodynamics
- Enthalpy of Reaction
- Hess' Law
- Wave Nature of Light
- Blackbody Radiation
- Photoelectric Effect
- Line Spectra
- Bohr Model & Rydberg Equation
- Wave-Particle Duality
- Heisenberg Uncertainty Princiiple
- Atomic Orbitals & Energy
- Pauli Exclusion Principle
- Aufbau Principle
- Electron configurations
- Periodic Trends: Size, IE, EA
- Ionic Bonding
- Covalent Bonding
- Lewis Structures
- Formal Charges and Resonance
- Strengths of Chemical Bonds
- Molecular Structure & Polarity
- Valence Bond Theory
- Hybrid Atomic Orbitals
- Multiple Bonds
- Molecular Orbital Theory
- Gas Pressure
- Simple Gas Laws
- Ideal Gas Law
- Gas Mixtures, Density, Partial Pressure
- Effusion and Diffusion of Gases
- Kinetic Molecular Theory of Gases
- Non-ideal Gas Behavior
- Intermolecular Forces
- Properties of Liquids
- Phase Transitions
- Phase Diagrams
- Solid State of Matter
- Lattice Structures of Crystalline Solids
- Semiconductors and Band Theory
- Dissolution Process
- Solubility
- Electrolytes
- Colligative Properties
- Colloids
- Chemical Reaction Rates
- Arrhenius Equation
- Rate Laws
- Integrated Rate Laws
- Collision Theory
- Reaction Mechanisms
- Catalysis
- Chemical Equilibria
- Equilibrium Constants
- Le Chatelier's Principle
- Equilibrium Calculations
- Bronsted-Lowry Acids & Bases
- pH and pOH
- Relative Strengths of Acids and Bases
- Hydrolysis of Salts
- Polyprotic Acids
- Buffer Solutions
- Acid-Base Titrations
- Slightly Soluble Salts
- Lewis Acids and Bases
- Coupled Equilibria
- Formation of Complex Ions
- Reaction Spontaneity
- Entropy
- 2nd & 3rd Laws of Thermodynamics
- Free Energy
- Carnot Engine
- Oxidation-Reduction Reactions
- Galvanic Cells
- Electrode and Cell Potentials
- Potential, Free Energy & Equilibrium
- Batteries & Fuel Cells
- Corrosion
- Electrolysis and Disproportionation
- Classification of Elements
- Representative Metals
- Metalloids
- Nonmetals
- Compounds of Hydrogen
- Carbonates
- Compounds of Nitrogen
- Compounds of Phosphorus
- Compounds of Oxygen
- Compounds of Sulfur
- Halogens
- Noble Gases
- Transition Metals and Compounds
- Coordination Chemistry
- Properties of Coordination Compounds
- Metallurgy
- Hydrocarbons
- Alcohols & Ethers
- Aldehydes, Ketones, Acids & Esters
- Amines and Amides
- Stereoisomers
- Polymers and Plastics
- Nuclear Structure & Stability
- Nuclear Equations
- Radioactive Decay
- Nuclear Fission & Fusion
- Uses of Radioisotopes
- Biological Effects of Radiation
- Lipids
- Carbohydrates
- Amino Acids and Proteins
- Nucleic Acids
- Chemical Separations
- Environmental Chemistry
- Molecular Spectroscopy

