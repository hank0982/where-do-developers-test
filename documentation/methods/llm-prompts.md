# LLM Prompt Inventory for Replication

This document records the exact prompt forms used by the current focal-method annotation pipeline in this workspace. The two relevant stages are:

1. the v4 iterative candidate-generation stage
2. the final-compress reconciliation stage

The verbatim examples below are taken from emitted request payloads, not reconstructed by hand.

## Prompt Stage A: v4 Iterative Candidate Generation

**Primary prompt builders**

- `scripts/LLM_batch/prompt.py`
- `scripts/LLM_batch/prompt_common.py`

**Example emitted request**

- `dbs/batch_input_commons-validator/attempt_v4_ollama_qwen_complete/ollama-requests.qwen3.5-cloud.round0.jsonl`

### Full system prompt

```text
You are analyzing a JUnit test and possible SUT method focus.
From the provided invoked methods, output:
1) focal_methods: A minimal set of SUT methods (including constructors) invoked by the test case whose behavior or interaction semantics capture the test intent.
2) requested_methods: methods you want to inspect in a deeper call step when confidence is low.

Inclusion criteria for focal_methods:
1. Method/constructor is declared within application source code (SUT). This is already pre-filtered by the script.
2. Method is executed during the test run.
3. If a method appears to be a utility/wrapper/delegator, or the intent is unclear, request deeper method-call inspection (up to depth 5).
4. Method contributes to the core behavior the test verifies.Assertions and test name/comments are cues for intent, but do not automatically make the asserted method focal.Record multiple focal methods only when the test case explicitly verify their interactions.

Exclusion criteria for focal_methods:
1. Exclude methods used purely for observation, scaffolding, setup, or state exposure (for example simple getters), unless that behavior itself is explicity mentioned in the test name or comments or implicitly inferable.
2. Exclude methods whose role is only to wrap/instantiate fixture setup used to reach behavior under test.

What inspection provides for each requested method in the next step:
- The direct subsequent invoked methods under that requested method for the same test execution.
- The source code of that requested method.
- This helps decide whether the requested method is just a wrapper/delegator or where behavior is actually implemented.

Rules:
- Return strict JSON only.
- Use only methods from the invoked-method list; never invent IDs, class names, or method names.
- focal_methods is a candidate set (not final): include all plausible focal candidates; do not under-report.
- If uncertain, inspect deeper: add the uncertain method to requested_methods when can_dive=1 and analysis_depth < max_analysis_depth.
- For uncertain utility/helper/wrapper/delegator/forwarding or reflection/dynamic-dispatch-like methods, keep requesting deeper inspection only while uncertainty remains (and can_dive=1, analysis_depth < max_analysis_depth).
- requested_methods should be small, include only methods that need deeper inspection, and may be non-empty even when focal_methods is non-empty.
- Count interaction of multiple methods as focal only when the test assertions explicitly target that interaction semantics.
- Methods used only as probes/observers (for example equals/getters) are non-focal unless directly specified in test name or comment.
- requested_methods must only include methods with can_dive=1; methods with can_dive=0 may still be focal_methods.
- You may request a candidate focal method again when unresolved uncertainty remains and deeper evidence is needed.
- If analysis_depth >= max_analysis_depth, requested_methods must be [].
- In dive steps, parent methods may remain focal; if uncertainty is resolved, requested_methods may be [].
- focal_methods and/or requested_methods may be empty.
Examples:
Test ID: 1620
Test name: org.jsoup.select.SelectorTest_descendant_3a0746be
Test method: descendant
Invoked methods:
- method_id=135 class_name=org.jsoup.Jsoup method_name=parse raw_method=parse/1[java.lang.String]
- method_id=734 class_name=org.jsoup.nodes.Element method_name=select raw_method=select/1[java.lang.String]
- method_id=803 class_name=org.jsoup.nodes.Element method_name=getElementsByClass raw_method=getElementsByClass/1[java.lang.String]
- method_id=822 class_name=org.jsoup.nodes.Element method_name=text raw_method=text/0
- method_id=1666 class_name=org.jsoup.select.Elements method_name=first raw_method=first/0
Answer: {"focal_methods":[{"test_id":1620,"method_id":734,"class_name":"org.jsoup.nodes.Element","method_name":"select"}],"requested_methods":[]}

Test ID: 1808
Test name: org.dyn4j.collision.shapes.SegmentCapsuleTest_detectSat_90e77643
Test method: detectSat
Invoked methods:
- method_id=999 class_name=org.dyn4j.collision.narrowphase.Sat method_name=detect raw_method=detect/4[org.dyn4j.geometry.Convex,org.dyn4j.geometry.Transform,org.dyn4j.geometry.Convex,org.dyn4j.geometry.Transform]
- method_id=1000 class_name=org.dyn4j.collision.narrowphase.Sat method_name=detect raw_method=detect/5[org.dyn4j.geometry.Convex,org.dyn4j.geometry.Transform,org.dyn4j.geometry.Convex,org.dyn4j.geometry.Transform,org.dyn4j.collision.narrowphase.Penetration]
- method_id=1016 class_name=org.dyn4j.collision.narrowphase.Penetration method_name=getNormal raw_method=getNormal/0
- method_id=1017 class_name=org.dyn4j.collision.narrowphase.Penetration method_name=clear raw_method=clear/0
- method_id=1019 class_name=org.dyn4j.collision.narrowphase.Penetration method_name=getDepth raw_method=getDepth/0
- method_id=2455 class_name=org.dyn4j.geometry.Transform method_name=translate raw_method=translate/2[double,double]
Answer: {"focal_methods":[{"test_id":1808,"method_id":999,"class_name":"org.dyn4j.collision.narrowphase.Sat","method_name":"detect"},{"test_id":1808,"method_id":1000,"class_name":"org.dyn4j.collision.narrowphase.Sat","method_name":"detect"}],"requested_methods":[]}

Test ID: 608
Test name: org.jfree.chart.plot.MeterPlotTest_testCloning_02e4ace9
Test method: testCloning
Invoked methods (first run level-0):
- method_id=6298 class_name=org.jfree.chart.plot.MeterPlot method_name=equals raw_method=equals/1[java.lang.Object]
- method_id=6304 class_name=org.jfree.chart.plot.MeterPlot method_name=getDataset raw_method=getDataset/0
- method_id=6321 class_name=org.jfree.chart.plot.MeterPlot method_name=addInterval raw_method=addInterval/1[org.jfree.chart.plot.MeterInterval]
- method_id=6334 class_name=org.jfree.chart.plot.MeterPlot method_name=getTickLabelFormat raw_method=getTickLabelFormat/0
- method_id=7157 class_name=org.jfree.chart.internal.CloneUtils method_name=clone raw_method=clone/1[T]
Answer (first run): {"focal_methods":[],"requested_methods":[{"test_id":608,"method_id":7157,"class_name":"org.jfree.chart.internal.CloneUtils","method_name":"clone"}]}
Invoked methods (after inspecting requested method 7157):
- method_id=6320 class_name=org.jfree.chart.plot.MeterPlot method_name=clone raw_method=clone/0
- method_id=6334 class_name=org.jfree.chart.plot.MeterPlot method_name=getTickLabelFormat raw_method=getTickLabelFormat/0
- method_id=6321 class_name=org.jfree.chart.plot.MeterPlot method_name=addInterval raw_method=addInterval/1[org.jfree.chart.plot.MeterInterval]
Answer (after inspecting requested method 7157): {"focal_methods":[{"test_id":608,"method_id":6320,"class_name":"org.jfree.chart.plot.MeterPlot","method_name":"clone"}],"requested_methods":[]}
```

### Full example user prompt

```text
Test ID: 1
Test name: org.apache.commons.validator.ByteTest_testByteBeyondMax_86ec4f7c
Test method: testByteBeyondMax

Analysis context:
- analysis_depth=0
- max_analysis_depth=6
- If analysis_depth >= max_analysis_depth, requested_methods must be [].

Level-0 methods are directly invoked by the test method obtained through instrumentation. Only select focal_methods and requested_methods from the invoked methods listed below.

Invoked methods (from level_zero or expanded calls):
- method_id=135 can_dive=0 class_name=org.apache.commons.validator.ValidatorResult method_name=containsAction raw_method=containsAction/1[java.lang.String]
- method_id=140 can_dive=1 class_name=org.apache.commons.validator.ValidatorResult method_name=isValid raw_method=isValid/1[java.lang.String]
- method_id=736 can_dive=0 class_name=org.apache.commons.validator.ValidatorResults method_name=getValidatorResult raw_method=getValidatorResult/1[java.lang.String]
- method_id=740 can_dive=0 class_name=org.apache.commons.validator.Validator method_name=Validator raw_method=Validator/2[org.apache.commons.validator.ValidatorResources,java.lang.String]
- method_id=751 can_dive=1 class_name=org.apache.commons.validator.Validator method_name=validate raw_method=validate/0
- method_id=753 can_dive=0 class_name=org.apache.commons.validator.Validator method_name=setParameter raw_method=setParameter/2[java.lang.String,java.lang.Object]

Direct subsequent method calls for each invoked method (one level deeper):
- parent_method_id=135 class_name=org.apache.commons.validator.ValidatorResult method_name=containsAction raw_method=containsAction/1[java.lang.String]
  - (no deeper calls captured)
- parent_method_id=140 class_name=org.apache.commons.validator.ValidatorResult method_name=isValid raw_method=isValid/1[java.lang.String]
  - method_id=149 can_dive=0 class_name=org.apache.commons.validator.ValidatorResult$ResultStatus method_name=isValid raw_method=isValid/0
- parent_method_id=736 class_name=org.apache.commons.validator.ValidatorResults method_name=getValidatorResult raw_method=getValidatorResult/1[java.lang.String]
  - (no deeper calls captured)
- parent_method_id=740 class_name=org.apache.commons.validator.Validator method_name=Validator raw_method=Validator/2[org.apache.commons.validator.ValidatorResources,java.lang.String]
  - (no deeper calls captured)
- parent_method_id=751 class_name=org.apache.commons.validator.Validator method_name=validate raw_method=validate/0
  - method_id=153 can_dive=1 class_name=org.apache.commons.validator.ValidatorResources method_name=getForm raw_method=getForm/2[java.util.Locale,java.lang.String]
  - method_id=175 can_dive=1 class_name=org.apache.commons.validator.ValidatorResources method_name=getValidatorActions raw_method=getValidatorActions/0
  - method_id=704 can_dive=1 class_name=org.apache.commons.validator.Form method_name=validate raw_method=validate/4[java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,org.apache.commons.validator.ValidatorAction>,int,java.lang.String]
  - method_id=753 can_dive=0 class_name=org.apache.commons.validator.Validator method_name=setParameter raw_method=setParameter/2[java.lang.String,java.lang.Object]
  - method_id=754 can_dive=0 class_name=org.apache.commons.validator.Validator method_name=getParameterValue raw_method=getParameterValue/1[java.lang.String]
- parent_method_id=753 class_name=org.apache.commons.validator.Validator method_name=setParameter raw_method=setParameter/2[java.lang.String,java.lang.Object]
  - (no deeper calls captured)

Invoked method source code:
- method_id=135 class_name=org.apache.commons.validator.ValidatorResult method_name=containsAction raw_method=containsAction/1[java.lang.String]
public boolean containsAction(final String validatorName) {
        return hAction.containsKey(validatorName);
    }

- method_id=140 class_name=org.apache.commons.validator.ValidatorResult method_name=isValid raw_method=isValid/1[java.lang.String]
public boolean isValid(final String validatorName) {
        final ResultStatus status = hAction.get(validatorName);
        return status != null && status.isValid();
    }

- method_id=736 class_name=org.apache.commons.validator.ValidatorResults method_name=getValidatorResult raw_method=getValidatorResult/1[java.lang.String]
public ValidatorResult getValidatorResult(final String key) {
        return hResults.get(key);
    }

- method_id=740 class_name=org.apache.commons.validator.Validator method_name=Validator raw_method=Validator/2[org.apache.commons.validator.ValidatorResources,java.lang.String]
public Validator(final ValidatorResources resources, final String formName) {
        if (resources == null) {
            throw new IllegalArgumentException("Resources cannot be null.");
        }

        this.resources = resources;
        this.formName = formName;
    }

- method_id=751 class_name=org.apache.commons.validator.Validator method_name=validate raw_method=validate/0
public ValidatorResults validate() throws ValidatorException {
        Locale locale = (Locale) getParameterValue(LOCALE_PARAM);

        if (locale == null) {
            locale = Locale.getDefault();
        }

        setParameter(VALIDATOR_PARAM, this);

        final Form form = resources.getForm(locale, formName);
        if (form != null) {
            setParameter(FORM_PARAM, form);
            return form.validate(
                parameters,
                resources.getValidatorActions(),
                page,
                fieldName);
        }

        return new ValidatorResults();
    }

- method_id=753 class_name=org.apache.commons.validator.Validator method_name=setParameter raw_method=setParameter/2[java.lang.String,java.lang.Object]
public void setParameter(final String parameterClassName, final Object parameterValue) {
        parameters.put(parameterClassName, parameterValue);
    }

Test file content:
package org.apache.commons.validator;

import org.junit.jupiter.api.Test;

/**
 * Performs Validation Test for {@code byte} validations.
 */
class ByteTest extends AbstractNumberTest {

public static final String MY_TEST_FILE_NAME = "src/test/java/org/apache/commons/validator/ByteTest.java";

    ByteTest() {
        action = "byte";
        formKey = "byteForm";
    }

    /**
     * Tests the byte validation.
     */
    @Test
    void testByte() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue("0");

        valueTest(info, true);
    }

    /**
     * Tests the byte validation failure.
     */
    @Test
    void testByteBeyondMax() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.MAX_VALUE + "1");

        valueTest(info, false);
    }

    /**
     * Tests the byte validation failure.
     */
    @Test
    void testByteBeyondMin() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.MIN_VALUE + "1");

        valueTest(info, false);
    }

    /**
     * Tests the byte validation failure.
     */
    @Test
    void testByteFailure() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();

        valueTest(info, false);
    }

    /**
     * Tests the byte validation.
     */
    @Test
    void testByteMax() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.toString(Byte.MAX_VALUE));

        valueTest(info, true);
    }

    /**
     * Tests the byte validation.
     */
    @Test
    void testByteMin() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.toString(Byte.MIN_VALUE));

        valueTest(info, true);
    }

}

Question: Which methods are focal_methods, and which methods are requested_methods for deeper inspection for the test case org.apache.commons.validator.ByteTest_testByteBeyondMax_86ec4f7c?
```

## Prompt Stage B: Final-Compress Reconciliation

**Primary prompt builders**

- `scripts/LLM_batch/run_v4_ollama.py`
- `scripts/LLM_batch/run_v4_gemini_chain.py`

**Example emitted request**

- `dbs/batch_input_commons-validator/attempt_v4_ollama_qwen_complete/ollama-requests.qwen3.5-cloud.final-compress.jsonl`

### Full system prompt

```text
You are reconciling focal-method candidates for one JUnit test.
Select a minimal final set of focal methods.

Definition - Focal Method(s):
A minimal set of SUT methods (including constructors) invoked by the test case whose behavior or interaction semantics capture the test intent.

Inclusion criteria:
1. Method/constructor is declared within application source code (SUT). This is already pre-filtered by the script.
2. Method is executed during the test run.
3. If a method appears to be a utility/wrapper/delegator, or the intent is unclear, request deeper method-call inspection (up to depth 5).
4. Method contributes to the core behavior the test verifies.Assertions and test name/comments are cues for intent, but do not automatically make the asserted method focal.Record multiple focal methods only when the test case explicitly verify their interactions.

Exclusion criteria:
1. Exclude methods used purely for observation, scaffolding, setup, or state exposure (for example simple getters), unless that behavior itself is explicity mentioned in the test name or comments or implicitly inferable.
2. Exclude methods whose role is only to wrap/instantiate fixture setup used to reach behavior under test.

Rules:
- Choose only from Candidate focal methods list.
- Count interaction of multiple methods as focal only when the test assertions explicitly target that interaction semantics.
- Methods used only as probes/observers (for example equals/getters) are non-focal unless directly specified in test name or comment.
- Never invent method IDs, class names, or method names.
- Return strict JSON only.

Output schema keys:
- focal_methods: [{test_id, method_id, class_name, method_name}]

No prose, markdown, or code fences.
```

### Full example user prompt

```text
Test ID: 1
Test name: org.apache.commons.validator.ByteTest_testByteBeyondMax_86ec4f7c
Test method: testByteBeyondMax

Invoked methods (level-0):
- method_id=135 class_name=org.apache.commons.validator.ValidatorResult method_name=containsAction raw_method=containsAction/1[java.lang.String]
- method_id=140 class_name=org.apache.commons.validator.ValidatorResult method_name=isValid raw_method=isValid/1[java.lang.String]
- method_id=736 class_name=org.apache.commons.validator.ValidatorResults method_name=getValidatorResult raw_method=getValidatorResult/1[java.lang.String]
- method_id=740 class_name=org.apache.commons.validator.Validator method_name=Validator raw_method=Validator/2[org.apache.commons.validator.ValidatorResources,java.lang.String]
- method_id=751 class_name=org.apache.commons.validator.Validator method_name=validate raw_method=validate/0
- method_id=753 class_name=org.apache.commons.validator.Validator method_name=setParameter raw_method=setParameter/2[java.lang.String,java.lang.Object]

Candidate focal methods (from prior rounds):
- method_id=56 class_name=org.apache.commons.validator.ValidatorAction method_name=executeValidationMethod raw_method=executeValidationMethod/4[org.apache.commons.validator.Field,java.util.Map<java.lang.String,java.lang.Object>,org.apache.commons.validator.ValidatorResults,int]
- method_id=625 class_name=org.apache.commons.validator.Field method_name=validate raw_method=validate/2[java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,org.apache.commons.validator.ValidatorAction>]
- method_id=662 class_name=org.apache.commons.validator.Field method_name=validateForRule raw_method=validateForRule/5[org.apache.commons.validator.ValidatorAction,org.apache.commons.validator.ValidatorResults,java.util.Map<java.lang.String,org.apache.commons.validator.ValidatorAction>,java.util.Map<java.lang.String,java.lang.Object>,int]
- method_id=720 class_name=org.apache.commons.validator.GenericTypeValidator method_name=formatByte raw_method=formatByte/1[java.lang.String]
- method_id=751 class_name=org.apache.commons.validator.Validator method_name=validate raw_method=validate/0

Candidate focal method source code:
- method_id=56 class_name=org.apache.commons.validator.ValidatorAction method_name=executeValidationMethod raw_method=executeValidationMethod/4[org.apache.commons.validator.Field,java.util.Map<java.lang.String,java.lang.Object>,org.apache.commons.validator.ValidatorResults,int]
  source_code:
    boolean executeValidationMethod(final Field field,
                // TODO What is this the correct value type?
                // both ValidatorAction and Validator are added as parameters
                final Map<String, Object> params, final ValidatorResults results, final int pos) throws ValidatorException {

            params.put(Validator.VALIDATOR_ACTION_PARAM, this);

            try {
                if (validationMethod == null) {
                    synchronized (this) {
                        final ClassLoader loader = getClassLoader(params);
                        loadValidationClass(loader);
                        loadParameterClasses(loader);
                        loadValidationMethod();
                    }
                }

                final Object[] paramValues = getParameterValues(params);

                if (field.isIndexed()) {
                    handleIndexedField(field, pos, paramValues);
                }

                Object result = null;
                try {
                    result = validationMethod.invoke(getValidationClassInstance(), paramValues);

                } catch (IllegalArgumentException | IllegalAccessException e) {
                    throw new ValidatorException(e.getMessage());
                } catch (final InvocationTargetException e) {

                    if (e.getTargetException() instanceof Exception) {
                        throw (Exception) e.getTargetException();

                    }
                    if (e.getTargetException() instanceof Error) {
                        throw (Error) e.getTargetException();
                    }
                }

                final boolean valid = isValid(result);
                if (!valid || valid && !onlyReturnErrors(params)) {
                    results.add(field, name, valid, result);
                }

                if (!valid) {
                    return false;
                }

                // TODO This catch block remains for backward compatibility. Remove
                // this for Validator 2.0 when exception scheme changes.
            } catch (final Exception e) {
                if (e instanceof ValidatorException) {
                    throw (ValidatorException) e;
                }

                getLog().error("Unhandled exception thrown during validation: " + e.getMessage(), e);

                results.add(field, name, false);
                return false;
            }

            return true;
        }
- method_id=625 class_name=org.apache.commons.validator.Field method_name=validate raw_method=validate/2[java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,org.apache.commons.validator.ValidatorAction>]
  source_code:
    public ValidatorResults validate(final Map<String, Object> params, final Map<String, ValidatorAction> actions)
                throws ValidatorException {

            if (getDepends() == null) {
                return new ValidatorResults();
            }

            final ValidatorResults allResults = new ValidatorResults();

            final Object bean = params.get(Validator.BEAN_PARAM);
            final int numberOfFieldsToValidate = isIndexed() ? getIndexedPropertySize(bean) : 1;

            for (int fieldNumber = 0; fieldNumber < numberOfFieldsToValidate; fieldNumber++) {

                final ValidatorResults results = new ValidatorResults();
                synchronized (dependencyList) {
                    for (final String depend : dependencyList) {

                        final ValidatorAction action = actions.get(depend);
                        if (action == null) {
                            handleMissingAction(depend);
                        }

                        final boolean good = validateForRule(action, results, actions, params, fieldNumber);

                        if (!good) {
                            allResults.merge(results);
                            return allResults;
                        }
                    }
                }
                allResults.merge(results);
            }

            return allResults;
        }
- method_id=662 class_name=org.apache.commons.validator.Field method_name=validateForRule raw_method=validateForRule/5[org.apache.commons.validator.ValidatorAction,org.apache.commons.validator.ValidatorResults,java.util.Map<java.lang.String,org.apache.commons.validator.ValidatorAction>,java.util.Map<java.lang.String,java.lang.Object>,int]
  source_code:
    private boolean validateForRule(
            final ValidatorAction va,
            final ValidatorResults results,
            final Map<String, ValidatorAction> actions,
            final Map<String, Object> params,
            final int pos)
            throws ValidatorException {

            final ValidatorResult result = results.getValidatorResult(getKey());
            if (result != null && result.containsAction(va.getName())) {
                return result.isValid(va.getName());
            }

            if (!runDependentValidators(va, results, actions, params, pos)) {
                return false;
            }

            return va.executeValidationMethod(this, params, results, pos);
        }
- method_id=720 class_name=org.apache.commons.validator.GenericTypeValidator method_name=formatByte raw_method=formatByte/1[java.lang.String]
  source_code:
    public static Byte formatByte(final String value) {
            if (value == null) {
                return null;
            }

            try {
                return Byte.valueOf(value);
            } catch (final NumberFormatException e) {
                return null;
            }

        }
- method_id=751 class_name=org.apache.commons.validator.Validator method_name=validate raw_method=validate/0
  source_code:
    public ValidatorResults validate() throws ValidatorException {
            Locale locale = (Locale) getParameterValue(LOCALE_PARAM);

            if (locale == null) {
                locale = Locale.getDefault();
            }

            setParameter(VALIDATOR_PARAM, this);

            final Form form = resources.getForm(locale, formName);
            if (form != null) {
                setParameter(FORM_PARAM, form);
                return form.validate(
                    parameters,
                    resources.getValidatorActions(),
                    page,
                    fieldName);
            }

            return new ValidatorResults();
        }

Test file content:
package org.apache.commons.validator;

import org.junit.jupiter.api.Test;

/**
 * Performs Validation Test for {@code byte} validations.
 */
class ByteTest extends AbstractNumberTest {

public static final String MY_TEST_FILE_NAME = "src/test/java/org/apache/commons/validator/ByteTest.java";

    ByteTest() {
        action = "byte";
        formKey = "byteForm";
    }

    /**
     * Tests the byte validation.
     */
    @Test
    void testByte() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue("0");

        valueTest(info, true);
    }

    /**
     * Tests the byte validation failure.
     */
    @Test
    void testByteBeyondMax() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.MAX_VALUE + "1");

        valueTest(info, false);
    }

    /**
     * Tests the byte validation failure.
     */
    @Test
    void testByteBeyondMin() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.MIN_VALUE + "1");

        valueTest(info, false);
    }

    /**
     * Tests the byte validation failure.
     */
    @Test
    void testByteFailure() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();

        valueTest(info, false);
    }

    /**
     * Tests the byte validation.
     */
    @Test
    void testByteMax() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.toString(Byte.MAX_VALUE));

        valueTest(info, true);
    }

    /**
     * Tests the byte validation.
     */
    @Test
    void testByteMin() throws ValidatorException {
        // Create bean to run test on.
        final ValueBean info = new ValueBean();
        info.setValue(Byte.toString(Byte.MIN_VALUE));

        valueTest(info, true);
    }

}

Question: Return the minimal final focal_methods set from candidate focal method.
```
