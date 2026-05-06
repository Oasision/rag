#!/bin/bash
# MinIO 鏂囦欢杩佺Щ鑴氭湰锛氬皢鏃ф枃浠讹紙鏂囦欢鍚嶈矾寰勶級杩佺Щ鍒版柊璺緞锛圡D5璺緞锛?
# 閰嶇疆
MYSQL_HOST="localhost"
MYSQL_USER="root"
MYSQL_PASS="123456"
MYSQL_DB="RAG"
MINIO_ALIAS="myminio"
MINIO_BUCKET="uploads"

echo "=== MinIO 鏂囦欢杩佺Щ鑴氭湰 ==="
echo "寮€濮嬫椂闂? $(date)"

# 1. 浠嶮ySQL鑾峰彇鎵€鏈夋枃浠惰褰曪紙鍖呭惈MD5鍜屾枃浠跺悕锛?echo ""
echo "姝ラ 1: 浠嶮ySQL鑾峰彇鏂囦欢璁板綍..."
mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASS $MYSQL_DB -N -e "
SELECT file_md5, file_name, user_id
FROM file_upload
WHERE status = 'COMPLETED'
ORDER BY created_at;" > /tmp/files_list.txt

echo "鎵惧埌 $(wc -l < /tmp/files_list.txt) 涓枃浠惰褰?

# 2. 閬嶅巻姣忎釜鏂囦欢锛岄噸鍛藉悕MinIO涓殑瀵硅薄
echo ""
echo "姝ラ 2: 閲嶅懡鍚峂inIO瀵硅薄..."
SUCCESS_COUNT=0
SKIP_COUNT=0
ERROR_COUNT=0

while IFS=$'\t' read -r file_md5 file_name user_id; do
    OLD_PATH="merged/$file_name"
    NEW_PATH="merged/$file_md5"

    echo "澶勭悊: $file_name (MD5: $file_md5)"

    # 妫€鏌ユ棫璺緞鏄惁瀛樺湪
    if mc stat $MINIO_ALIAS/$MINIO_BUCKET/$OLD_PATH >/dev/null 2>&1; then

        # 妫€鏌ユ柊璺緞鏄惁宸插瓨鍦?        if mc stat $MINIO_ALIAS/$MINIO_BUCKET/$NEW_PATH >/dev/null 2>&1; then
            echo "  鈿狅笍  鏂拌矾寰勫凡瀛樺湪锛屽垹闄ゆ棫璺緞"
            mc rm $MINIO_ALIAS/$MINIO_BUCKET/$OLD_PATH
            SKIP_COUNT=$((SKIP_COUNT + 1))
        else
            # 澶嶅埗鍒版柊璺緞
            if mc cp $MINIO_ALIAS/$MINIO_BUCKET/$OLD_PATH $MINIO_ALIAS/$MINIO_BUCKET/$NEW_PATH; then
                # 鍒犻櫎鏃ц矾寰?                mc rm $MINIO_ALIAS/$MINIO_BUCKET/$OLD_PATH
                echo "  鉁?杩佺Щ鎴愬姛: $OLD_PATH -> $NEW_PATH"
                SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
            else
                echo "  鉂?杩佺Щ澶辫触: $OLD_PATH"
                ERROR_COUNT=$((ERROR_COUNT + 1))
            fi
        fi
    else
        echo "  鈿狅笍  鏃ц矾寰勪笉瀛樺湪锛屽彲鑳藉凡杩佺Щ"
        SKIP_COUNT=$((SKIP_COUNT + 1))
    fi

done < /tmp/files_list.txt

# 3. 楠岃瘉杩佺Щ缁撴灉
echo ""
echo "姝ラ 3: 楠岃瘉杩佺Щ缁撴灉..."
echo "MinIO merged 鐩綍鍐呭:"
mc ls $MINIO_ALIAS/$MINIO_BUCKET/merged/

# 娓呯悊涓存椂鏂囦欢
rm -f /tmp/files_list.txt

# 鎬荤粨
echo ""
echo "=== 杩佺Щ瀹屾垚 ==="
echo "鎴愬姛杩佺Щ: $SUCCESS_COUNT 涓枃浠?
echo "璺宠繃/宸插瓨鍦? $SKIP_COUNT 涓枃浠?
echo "澶辫触: $ERROR_COUNT 涓枃浠?
echo "缁撴潫鏃堕棿: $(date)"
