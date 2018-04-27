#/bin/bash

# ./wait-and-submit './run-align-p0.sh' 'run-align-p1.sh' ...

for job in "$@"
do
	echo "executing job '$job' at `date`"
	eval $job
	while : ; do
		sleep 2
		running_jobs=`bjobs | grep saal | wc -l`
	#	echo "waiting for $running_jobs nodes to finish"
		[[ $running_jobs -gt 0 ]] || break
	done
	echo "job '$job' finished at `date`"
done
