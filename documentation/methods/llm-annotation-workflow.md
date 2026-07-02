# LLM Annotation Workflow (v4)

## 1. Round 0 (Initial Context)

- Input is `batch-input.jsonl` test records.
- `scripts/LLM_batch/requests.v4.py` builds one prompt per test with:
  - `analysis_depth=0`
  - Level-0 invoked SUT methods (`invoked_methods`)
  - One-level direct subsequent calls per invoked method
  - Source code for invoked methods
  - Test source code (test file content)
- LLM returns strict JSON:
  - `focal_methods` (candidate focal methods)
  - `requested_methods` (methods to inspect deeper when uncertain)

## 2. How Subsequent Rounds Are Selected

- LLM should place methods in `requested_methods` when they look like wrapper/delegator/unclear.
- Constraints:
  - Must come from invoked list
  - Must have `can_dive=1`
  - Must satisfy `analysis_depth < max_analysis_depth`
- Collector (`--collect-from`) parses model output and creates next `request.jsonl` rows with:
  - `requested_method_id`
  - `analysis_depth = previous_depth + 1`
  - `candidate_focal_methods` (union accumulated so far for that test)

## 3. Round 1..N (Deeper Context)

- For each `requested_method_id`, `requests.v4.py` expands calls under that parent method for the same test execution.
- New prompt includes:
  - Expanded invoked methods (children under requested parent)
  - Parent method metadata/source
  - Updated `candidate_focal_methods`
- LLM again outputs `focal_methods` and next `requested_methods`.
- Repeat until:
  - No more requests, or
  - `max_analysis_depth` is reached.

## 4. Why This Is Needed (Focal Under Call Graph)

Example: `MeterPlotTest_testCloning`

- Round 0 often surfaces `CloneUtils.clone` plus observation/probe methods.
- `CloneUtils.clone` can be a wrapper utility, so LLM requests a deeper step.
- The next round reveals `MeterPlot.clone` under the call graph.
- Final focal becomes `MeterPlot.clone`, which matches test intent better than the wrapper.

Without follow-up rounds, wrapper methods are more likely to be mislabeled as focal.

## 5. Final Compression Stage

- After rounds finish, chain runs final reconciliation (`--final-compress`):
  - Input: unioned candidate focal methods per test
  - Goal: minimal final focal set
- Guard validation checks that selected final methods are from candidate pool.
- Candidate pool is now discovered from available `output.round*.jsonl` files in `workdir`.
- If a final output contains out-of-candidate methods, only failed tests are selectively retried.

## 6. Concrete Example: MeterPlot Cloning

### Test Method

File: `java_repos/jfreechart/src/test/java/org/jfree/chart/plot/MeterPlotTest.java`

```java
@Test
public void testCloning() throws CloneNotSupportedException {
    MeterPlot p1 = new MeterPlot();
    MeterPlot p2 = CloneUtils.clone(p1);
    assertNotSame(p1, p2);
    assertSame(p1.getClass(), p2.getClass());
    assertEquals(p1, p2);

    // the clone and the original share a reference to the SAME dataset
    assertSame(p1.getDataset(), p2.getDataset());

    p1.getTickLabelFormat().setMinimumIntegerDigits(99);
    assertNotEquals(p1, p2);
    p2.getTickLabelFormat().setMinimumIntegerDigits(99);
    assertEquals(p1, p2);

    p1.addInterval(new MeterInterval("Test", new Range(1.234, 5.678)));
    assertNotEquals(p1, p2);
    p2.addInterval(new MeterInterval("Test", new Range(1.234, 5.678)));
    assertEquals(p1, p2);
}
```

### Relevant SUT Methods

`CloneUtils.clone` is the entry wrapper used by the test:

File: `java_repos/jfreechart/src/main/java/org/jfree/chart/internal/CloneUtils.java`

```java
public static <T> T clone(T object) throws CloneNotSupportedException {
    if (object == null) {
        return null;
    }
    if (object instanceof PublicCloneable) {
        PublicCloneable pc = (PublicCloneable) object;
        return (T) pc.clone();
    } else {
        // reflection path...
    }
    throw new CloneNotSupportedException("Failed to clone.");
}
```

`MeterPlot.clone` contains the behavior actually validated by this test:

File: `java_repos/jfreechart/src/main/java/org/jfree/chart/plot/MeterPlot.java`

```java
@Override
public Object clone() throws CloneNotSupportedException {
    MeterPlot clone = (MeterPlot) super.clone();
    clone.tickLabelFormat = (NumberFormat) this.tickLabelFormat.clone();
    // the following relies on the fact that the intervals are immutable
    clone.intervals = new ArrayList<>(this.intervals);
    if (clone.dataset != null) {
        clone.dataset.addChangeListener(clone);
    }
    return clone;
}
```

Additional methods used by the assertions:

```java
public NumberFormat getTickLabelFormat() {
    return this.tickLabelFormat;
}

public void addInterval(MeterInterval interval) {
    Args.nullNotPermitted(interval, "interval");
    intervals.add(interval);
    fireChangeEvent();
}
```

### Instrumented Call Graph (Depth <= 2)

From `dbs/jfreechart.db` (`calls` joined with `method_signatures`) for `test_id=608`:

1. Depth 0: `7157` `org.jfree.chart.internal.CloneUtils.clone/1[T]`
2. Depth 1: `6320` `org.jfree.chart.plot.MeterPlot.clone/0`
3. Depth 2: `5826` `org.jfree.chart.plot.Plot.clone/0`

Why this matters:

- At round 0, the top visible call can look like `CloneUtils.clone` (wrapper).
- The dive round shows the delegated implementation `MeterPlot.clone`.
- Therefore, multi-round inspection prevents selecting only wrapper methods and improves focal-method precision.
