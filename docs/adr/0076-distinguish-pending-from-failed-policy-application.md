# Distinguish pending from failed Policy Application

Policy Application State will distinguish a desired version that is still pending from one that Child Mode has permanently rejected. Transient delivery, connectivity, and activation failures remain pending and retry, while a non-retryable rejection records only a safe machine-readable failure reason and allows Guardian Mode to stop presenting indefinite progress; a later valid policy version returns the application state to pending.
