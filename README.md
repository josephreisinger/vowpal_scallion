vowpal_scallion
===============

scala wrapper for vowpal wabbit


### Prereq
You need vw installed on your path.

### Build
```mvn clean install```

### Try it out
This will train a classifier on lines from "alice in wonderland" and "through the looking glass":
```gzcat training_data/alice.train.gz| bin/train --model models/vw_alice.scala```
The resulting model (and cache file) will be in model/vw_alice/


