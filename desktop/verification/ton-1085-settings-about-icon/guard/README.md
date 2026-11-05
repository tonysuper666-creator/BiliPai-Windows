# About selection and loader guard

Base: PR71 `24572f68afe005a5d970cd33724cca2197653032`, itself based on PR61 `16dfad2db5e83fdf5d5c7868e41eba3942577909`. The original foreground literal expectation fails even though the selected model now follows the user's icon and appearance; the replacement checks selection input, observed appearance, `remember` keys, the resolver, forwarding and the `AsyncImage` model. The generated desktop card converts that same name and current system theme to a URI. The new desktop test calls the application image loader with those URI models, checks decoded nontransparent pixels for 8 icon choices × 3 appearances × 2 system themes, and requires a missing URI to fail. It also checks that distinct key, appearance and day/night inputs produce distinct models. The PR71 integer-model loader fix and its independent acceptance remain prerequisites; this batch does not replace those tests.

Run on macOS with JDK 21, cached Gradle dependencies, and no other heavy build running:

```sh
desktop/verification/ton-1085-settings-about-icon/guard/run.sh ../new-about-guard-output
```

The script creates the shared build lock, refuses to run if another build is found after locking, runs the JUnit 25-case baseline and candidate, five separately mutated source counterexamples and restored candidate, then runs the targeted Gradle test. It does not run Windows or GUI validation. `verify.py` stores actual per-case JUnit XML and command logs in the new output directory; it refuses to overwrite an existing directory. The JUnit harness uses cached Kotlin and JUnit jars, independently of Gradle's upstream registration. The targeted Gradle command excludes the unrelated upstream source and sponsor finalizers; it is not a full gate.

`root-registration.diff` proposes the four missing PR61 test-correction registrations against the candidate build file. It is not applied. Root must review it together with the complete integration sequence and re-evaluate it against the final integration tip.
