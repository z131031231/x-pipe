DIR=`dirname $0`
IPLIST_PATH="$DIR/iplists"
LOGPATH="$DIR/logs/add_cron"
SCRIPT=add_cron.sh

cancel=$1
if [ "$cancel" == "cancel" ]; then
    LOGPATH="$DIR/logs/cancel_cron"
    SCRIPT=cancel_cron.sh
fi

mkdir -p $LOGPATH

username=xpipe
password=12qwaszx

ssh_iplist=()
iplist=( `ls $IPLIST_PATH` )
for ip in ${iplist[@]};
do
    ssh_iplist+=($ip)
done

echo ${iplist[@]}

while [ ${#ssh_iplist[@]} -gt 0 ]; do
    for src_ip in ${ssh_iplist[@]};
    do
        cmd="echo $password | sudo -S \"/home/xpipe/remote_cron/$SCRIPT\""

        nohup sshpass -p"$password" ssh -o StrictHostKeyChecking=no $username@$src_ip $cmd > "$LOGPATH/$src_ip.log" 2>&1 &
        cmd=""
    done

    #waiting
    echo "triggering add cron.....\c"
    while [ $((`ps -ef | grep sshpass | grep -v grep | wc -l`)) -gt 0 ]; do
        echo ".....\c"
        sleep 0.5
    done
    echo "done!"

    retry_ip=()
    for src_ip in ${ssh_iplist[@]};
    do
        if [ $((`cat "$LOGPATH/$src_ip.log" | grep -v "nohup: ignoring input" | grep -v UTF | grep -v "Permanently added" | wc -l`)) -eq 0 ]; then
            echo "$src_ip triggered success"
        else
            retry_ip+=($src_ip)
            echo "Error. $src_ip triggered failed. Due to:"
            echo `cat "$LOGPATH/$src_ip.log"`
        fi
    done
    echo "retrylist=${retry_ip[*]}"
    ssh_iplist=(${retry_ip[*]})
done

