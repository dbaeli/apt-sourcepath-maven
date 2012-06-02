CLASSPATH="../base/target/base-0.1-SNAPSHOT.jar:target/classes"
PROCESSOR="org.test.apt.TestProcessor"
SOURCE_PATH="src/main/java"
FILES="src/main/java/org/test/sample/InSrcDir.java"
javac -cp $CLASSPATH  -proc:only -encoding UTF-8 -processor $PROCESSOR -d target/classes -s target/generated-sources -verbose $FILES

