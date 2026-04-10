package com.mohammadag.knockcode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.mohammadag.knockcode.SettingsHelper.OnSettingsReloadedListener;

public class KnockCodeView extends View implements OnSettingsReloadedListener {
	public enum Mode {
		READY, CORRECT, INCORRECT, DISABLED
	}

	private Paint mLinePaint;
	private Paint mInnerPaint;

	private int mPosition = -1;

	private OnPositionTappedListener mListener;

	private float mLineWidth;
	private Mode mMode = Mode.READY;
	private SettingsHelper mSettingsHelper;
	protected boolean mLongClick;
	private OnLongClickListener mLongClickListener;

	public interface OnPositionTappedListener {
		void onPositionTapped(int pos);
	}

	public KnockCodeView(Context context) {
		super(context);
		init(context);
	}

	private void init(Context context) {
		DisplayMetrics dm = context.getResources().getDisplayMetrics();
		mLineWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, dm);
		super.setOnLongClickListener(new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				if (mLongClickListener != null) {
					v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
					mLongClick = true;
					return mLongClickListener.onLongClick(KnockCodeView.this);
				}
				return false;
			}
		});
	}

	public KnockCodeView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public KnockCodeView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(context);
	}

	public void setOnPositionTappedListener(OnPositionTappedListener listener) {
		mListener = listener;
	}

	public void setSettingsHelper(SettingsHelper settingsHelper) {
		mSettingsHelper = settingsHelper;
		mSettingsHelper.addOnReloadListener(this);
	}

	private boolean shouldDrawLines() {
		if (mSettingsHelper != null) {
			return mSettingsHelper.shouldDrawLines();
		} else {
			return true;
		}
	}

	private boolean shouldDrawFill() {
		if (mSettingsHelper != null) {
			return mSettingsHelper.shouldDrawFill();
		} else {
			return true;
		}
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		if (mLinePaint == null) {
			mLinePaint = new Paint();
			mLinePaint.setColor(Color.GRAY);
			mLinePaint.setStrokeWidth(mLineWidth);
			mLinePaint.setAntiAlias(true);

			mInnerPaint = new Paint();
			mInnerPaint.setColor(Color.WHITE);
			mInnerPaint.setAntiAlias(true);
			mInnerPaint.setStyle(Paint.Style.FILL);
		}

		switch (mMode) {
		case READY:
			mLinePaint.setColor(Color.GRAY);
			break;
		case CORRECT:
			mLinePaint.setColor(Color.GREEN);
			break;
		case INCORRECT:
			mLinePaint.setColor(Color.RED);
			break;
		case DISABLED:
			mLinePaint.setColor(Color.BLACK);
			break;
		}

		if (!isEnabled()) {
			mLinePaint.setColor(Color.BLACK);
		}

		if (shouldDrawLines()) {
			// vertical divider
			canvas.drawLine(getWidth() / 2f, 0, getWidth() / 2f, getHeight(), mLinePaint);
			// two horizontal dividers — 3 rows
			canvas.drawLine(0, getHeight() / 3f, getWidth(), getHeight() / 3f, mLinePaint);
			canvas.drawLine(0, getHeight() * 2f / 3f, getWidth(), getHeight() * 2f / 3f, mLinePaint);
		}

		if (mPosition != -1 && shouldDrawFill()) {
			canvas.drawRect(getRectForPosition(mPosition), mInnerPaint);
		}
	}

	@Override
	public void setOnClickListener(OnClickListener l) {
		throw new RuntimeException("Unsupported");
	}

	@Override
	public void setOnLongClickListener(OnLongClickListener l) {
		mLongClickListener = l;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (!isEnabled())
			return true;

		switch (event.getAction()) {
		case MotionEvent.ACTION_DOWN:
			mPosition = getPositionOfClick(event.getX(), event.getY());
			invalidate();
			break;
		case MotionEvent.ACTION_UP:
			if (mLongClick) {
				mPosition = -1;
				invalidate();
				mLongClick = false;
				return super.onTouchEvent(event);
			} else {
				int position = getPositionOfClick(event.getX(), event.getY());
				if (mPosition != position) {
					mPosition = -1;
					break;
				}

				if (mListener != null)
					mListener.onPositionTapped(position);
			}

			mPosition = -1;
			invalidate();
			return super.onTouchEvent(event);
		}
		invalidate();
		return super.onTouchEvent(event);
	}

	private int getPositionOfClick(float x, float y) {
		int col = (x >= getWidth() / 2f) ? 1 : 0;   // 0 = left, 1 = right
		int row;
		if      (y < getHeight() / 3f)        row = 0;   // top
		else if (y < getHeight() * 2f / 3f)   row = 1;   // middle
		else                                   row = 2;   // bottom
		return row * 2 + col + 1;   // 1-indexed: 1..6
	}

	private Rect getRectForPosition(int pos) {
		return normalizeRect(getRectForPositionImpl(pos));
	}

	private Rect getRectForPositionImpl(int pos) {
		int w2 = getWidth() / 2;
		int h3 = getHeight() / 3;
		switch (pos) {
		case 1: return new Rect(0,  0,       w2,          h3);
		case 2: return new Rect(w2, 0,       getWidth(),  h3);
		case 3: return new Rect(0,  h3,      w2,          h3 * 2);
		case 4: return new Rect(w2, h3,      getWidth(),  h3 * 2);
		case 5: return new Rect(0,  h3 * 2,  w2,          getHeight());
		case 6: return new Rect(w2, h3 * 2,  getWidth(),  getHeight());
		default:
			throw new IllegalArgumentException("Only position 1-6 supported");
		}
	}

	private Rect normalizeRect(Rect rect) {
		Rect newRect = rect;
		if (rect.top > 0) {
			newRect.top = rect.top + (int) mLineWidth;
		}
		if (rect.left > 0) {
			newRect.left = rect.left + (int) mLineWidth;
		}
		if (rect.right > 0) {
			newRect.right = rect.right - (int) mLineWidth;
		}
		if (rect.bottom > 0) {
			newRect.bottom = rect.bottom - (int) mLineWidth;
		}

		return newRect;
	}

	@Override
	public void setEnabled(boolean enabled) {
		if (!enabled) {
			mMode = Mode.DISABLED;
		}
		super.setEnabled(enabled);
		invalidate();
	}

	public void setMode(Mode mode) {
		if (mMode == mode)
			return;

		mMode = mode;
		if (mode == Mode.DISABLED && isEnabled()) {
			setEnabled(false);
		}

		invalidate();
	}

	@Override
	public void onSettingsReloaded() {
		invalidate();
	}
}
