echo 
echo "     自动签名软件  nx111修改 "
echo ================================
tooldir=`dirname $0`
curdir=`pwd`

if [ $# -eq 0 ]; then
    if [ ! -d $tooldir/待签名 ]; then
         echo 请将待签名的zip/apk文件放入 签名工具下的\"待签名\"目录下
         mkdir $tooldir/待签名
         exit -1
    fi 

    [ -d $tooldir/已签名 ] || mkdir $tooldir/已签名
    echo 正在签名，请稍等.....
    cd $tooldir
    for f in 待签名/*.zip 待签名/*.apk; do 
	   fname=`basename $f`
	   [ -e $f ] && java -jar signapk.jar testkey.x509.pem testkey.pk8 $f  已签名/$fname
    done
    echo 签名完成.
    ls -l 已签名/
else
    if [ ! -f $1 ]; then
        echo "$1 未找到"
        exit -1
    fi
    filename=$(basename $1)
    java -jar $tooldir/signapk.jar $tooldir/testkey.x509.pem $tooldir/testkey.pk8 $1 $(dirname $1)/${filename%.*}-signed.${filename##*.}

fi

