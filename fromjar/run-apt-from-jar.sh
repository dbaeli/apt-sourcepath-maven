CLASSPATH="../base/target/base-0.1-SNAPSHOT.jar:target/classes"
PROCESSOR="org.test.apt.TestProcessor"
SOURCE_PATH="../example/target/example-0.1-SNAPSHOT.jar"
FILES="org.test.sample.InJarDir"
mkdir -p target/generated-sources
mkdir -p target/classes
CMD="javac -cp $CLASSPATH  -proc:only -encoding UTF-8 -processor $PROCESSOR -d target/classes -s target/generated-sources -sourcepath ${SOURCE_PATH} -verbose $FILES"
echo $CMD
$CMD
