package com.slide.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/** Native editor host for testing real InputConnection callbacks; absent from releases. */
class EditorTestActivity : Activity() {
    lateinit var editor: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs) ?: return null
                val mode = intent.getStringExtra("connectionMode")
                return object : InputConnectionWrapper(connection, false) {
                    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
                        if (mode == "no-surrounding-text") null else super.getTextBeforeCursor(n, flags)

                    override fun getSelectedText(flags: Int): CharSequence? =
                        if (mode == "hide-selected-text") null else super.getSelectedText(flags)

                }
            }
        }.apply {
            inputType = intent.getIntExtra("inputType", InputType.TYPE_CLASS_TEXT)
            setText(intent.getStringExtra("text").orEmpty())
            setSelection(intent.getIntExtra("selectionStart", text.length), text.length)
        }
        setContentView(editor)
        editor.requestFocus()
        editor.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
