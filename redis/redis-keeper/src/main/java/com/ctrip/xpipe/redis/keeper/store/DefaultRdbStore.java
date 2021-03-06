package com.ctrip.xpipe.redis.keeper.store;

import com.ctrip.xpipe.api.utils.ControllableFile;
import com.ctrip.xpipe.api.utils.FileSize;
import com.ctrip.xpipe.netty.ByteBufUtils;
import com.ctrip.xpipe.netty.filechannel.ReferenceFileChannel;
import com.ctrip.xpipe.netty.filechannel.ReferenceFileRegion;
import com.ctrip.xpipe.redis.core.protocal.protocal.EofMarkType;
import com.ctrip.xpipe.redis.core.protocal.protocal.EofType;
import com.ctrip.xpipe.redis.core.protocal.protocal.LenEofType;
import com.ctrip.xpipe.redis.core.store.RdbFileListener;
import com.ctrip.xpipe.redis.core.store.RdbStore;
import com.ctrip.xpipe.redis.core.store.RdbStoreListener;
import com.ctrip.xpipe.utils.DefaultControllableFile;
import com.ctrip.xpipe.utils.SizeControllableFile;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public class DefaultRdbStore extends AbstractStore implements RdbStore {

	private final static Logger logger = LoggerFactory.getLogger(DefaultRdbStore.class);
	
	public static final long FAIL_RDB_LENGTH = -1; 
	
	private RandomAccessFile writeFile;

	protected File file;

	private FileChannel channel;

	protected EofType eofType;

	private AtomicReference<Status> status = new AtomicReference<>(Status.Writing);

	protected long rdbOffset;

	private AtomicInteger refCount = new AtomicInteger(0);
	
	private List<RdbStoreListener> rdbStoreListeners = new LinkedList<>();
	
	private Object truncateLock = new Object();
	
	public DefaultRdbStore(File file, long rdbOffset, EofType eofType) throws IOException {

		this.file = file;
		this.eofType = eofType;
		this.rdbOffset = rdbOffset;
		
		if(file.length() > 0){
			checkAndSetRdbState();
		}else{
			writeFile = new RandomAccessFile(file, "rw");
			channel = writeFile.getChannel();
		}
	}
	
	@Override
	public int writeRdb(ByteBuf byteBuf) throws IOException {
		makeSureOpen();

		int wrote = ByteBufUtils.writeByteBufToFileChannel(byteBuf, channel);
		return wrote;
	}

	@Override
	public void truncateEndRdb(int reduceLen) throws IOException {
		
		logger.info("[truncateEndRdb]{}, {}", this, reduceLen);
		
		synchronized (truncateLock) {
			channel.truncate(channel.size() - reduceLen);
			endRdb();
		}
	}

	@Override
	public void endRdb() {
		
		if(status.get() != Status.Writing){
			logger.info("[endRdb][already ended]{}, {}, {}", this, file, status);
			return;
		}
		
		try{
			checkAndSetRdbState();
		}finally{
			notifyListenersEndRdb();
			try {
				writeFile.close();
			} catch (IOException e) {
				logger.error("[endRdb]" + this, e);
			}
		}
	}

	private void notifyListenersEndRdb() {
		
		for(RdbStoreListener listener : rdbStoreListeners){
			try{
				listener.onEndRdb();
			}catch(Throwable th){
				logger.error("[notifyListenersEndRdb]" + this, th);
			}
		}
	}

	@Override
	public void failRdb(Throwable throwable) {
		
		logger.info("[failRdb]" + this, throwable);
		
		if(status.get() != Status.Writing){
			throw new IllegalStateException("already finished with final state:" + status.get());
		}
		
		status.set(Status.Fail);
		notifyListenersEndRdb();
		try {
			writeFile.close();
		} catch (IOException e1) {
			logger.error("[failRdb]" + this, e1);
		}
	}

	@Override
	public long rdbFileLength() {
		
		if(status.get() == Status.Fail){
			return FAIL_RDB_LENGTH;
		}
		return file.length();
	}

	private void checkAndSetRdbState() {
		
		//TODO check file format
		if(eofType.fileOk(file)){
			status.set(Status.Success);
			logger.info("[checkAndSetRdbState]{}, {}", this, status);
		} else {
			status.set(Status.Fail);
			long actualFileLen = file.length();
			logger.error("[checkAndSetRdbState]actual:{}, expected:{}, file:{}, status:{}", actualFileLen, eofType, file, status);
		}
	}

	@Override
	public void readRdbFile(final RdbFileListener rdbFileListener) throws IOException {
		
		makeSureOpen();

		rdbFileListener.beforeFileData();
		refCount.incrementAndGet();

		try (ReferenceFileChannel channel = new ReferenceFileChannel(createControllableFile())) {
			doReadRdbFile(rdbFileListener, channel);
		} catch (Exception e) {
			logger.error("[readRdbFile]Error read rdb file" + file, e);
		}finally{
			refCount.decrementAndGet();
		}
	}

	private void doReadRdbFile(RdbFileListener rdbFileListener, ReferenceFileChannel referenceFileChannel) throws IOException {
		
		rdbFileListener.setRdbFileInfo(eofType, rdbOffset);

		long lastLogTime = System.currentTimeMillis();
		while (rdbFileListener.isOpen() && (isRdbWriting(status.get()) || (status.get() == Status.Success && referenceFileChannel.hasAnythingToRead()))) {
			
		ReferenceFileRegion referenceFileRegion = referenceFileChannel.readTilEnd();
		
		rdbFileListener.onFileData(referenceFileRegion);
		if(referenceFileRegion.count() <= 0)
			try {
				Thread.sleep(1);
				long currentTime = System.currentTimeMillis();
				if(currentTime - lastLogTime > 10000){
					logger.info("[doReadRdbFile]status:{}, referenceFileChannel:{}, count:{}, rdbFileListener:{}", 
							status.get(), referenceFileChannel, referenceFileRegion.count(), rdbFileListener);
					lastLogTime = currentTime;
				}
			} catch (InterruptedException e) {
				logger.error("[doReadRdbFile]" + rdbFileListener, e);
				Thread.currentThread().interrupt();
			}
		}

		logger.info("[doReadRdbFile] done with status {}", status.get());

		switch (status.get()) {
			case Success:
				if(file.exists()){//this is necessery because file may be deleted
					rdbFileListener.onFileData(null);
				}else{
					rdbFileListener.exception((new Exception("rdb file not exists now " + file)));
				}
				break;
	
			case Fail:
				rdbFileListener.exception(new Exception("[rdb error]" + file));
				break;
			default:
				rdbFileListener.exception(new Exception("[status not right]" + file + "," + status));
				break;
		}
	}

	private boolean isRdbWriting(Status status) {
		return status != Status.Fail && status != Status.Success;
	}

	@Override
	public int refCount() {
		return refCount.get();
	}

	@Override
	public long rdbOffset() {
		return rdbOffset;
	}
	
	public void incrementRefCount() {
		refCount.incrementAndGet();
	}
	
	public void decrementRefCount() {
		refCount.decrementAndGet();
	}

	@Override
	public boolean checkOk() {
		return status.get() == Status.Writing 
				|| ( status.get() == Status.Success && file.exists());
	}

	@Override
	public void destroy() throws Exception {
		
		logger.info("[destroy][delete file]{}", file);
		file.delete();
	}

	@Override
	public void close() throws IOException {
		
		if(cmpAndSetClosed()){
			logger.info("[close]{}", file);
			if(writeFile != null){
				writeFile.close();
			}
		}else{
			logger.warn("[close][already closed]{}", this);
		}
	}

	@Override
	public void addListener(RdbStoreListener rdbStoreListener) {
		rdbStoreListeners.add(rdbStoreListener);
	}

	@Override
	public void removeListener(RdbStoreListener rdbStoreListener) {
		rdbStoreListeners.remove(rdbStoreListener);
	}

	private ControllableFile createControllableFile() throws IOException {
		
		if(eofType instanceof LenEofType){
			return new DefaultControllableFile(file);
		}else if(eofType instanceof EofMarkType){
			
			return new SizeControllableFile(file, new FileSize() {
				
				@Override
				public long getSize(LongSupplier realSizeProvider) {
					
					long realSize = 0;
					synchronized (truncateLock) {//truncate may make size wrong
						realSize = realSizeProvider.getAsLong();
					}
					
					if(status.get() == Status.Writing){
						
						long ret = realSize - ((EofMarkType)eofType).getTag().length(); 
						logger.debug("[getSize][writing]{}, {}", DefaultRdbStore.this, ret);
						return ret < 0 ? 0 : ret;
					}
					return realSize;
				}
			});
		}else{
			throw new IllegalStateException("unknown eoftype:" + eofType.getClass() + "," + eofType);
		}
	}

	@Override
	public String toString() {
		return String.format("eofType:%s, rdbOffset:%d,file:%s, exists:%b, status:%s", eofType, rdbOffset, file, file.exists(), status.get());
	}

	@Override
	public boolean sameRdbFile(File file) {
		return this.file.equals(file);
	}

	@Override
	public String getRdbFileName() {
		if (null != file) {
			return file.getName();
		}

		return null;
	}

	@Override
	public boolean isWriting() {
		return isRdbWriting(status.get());
	}

	@Override
	public long getRdbFileLastModified() {
		return file.lastModified();
	}

}