"""Stable application error types and the Java-compatible JSON error response."""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response


class ApiError(Exception):
    """Expected API failure carrying an HTTP status and stable machine-readable code."""

    def __init__(self, status: int, code: str, message: str) -> None:
        self.status = status
        self.code = code
        self.message = message


def install_error_handlers(app: FastAPI) -> None:
    """Register consistent application errors while retaining intentionally empty 401/404 responses."""

    @app.exception_handler(ApiError)
    async def api_error_handler(_request: Request, exception: ApiError) -> Response:
        if exception.status in (401, 404) and not exception.message:
            return Response(status_code=exception.status)
        return JSONResponse(
            status_code=exception.status, content={"code": exception.code, "message": exception.message}
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(_request: Request, _exception: RequestValidationError) -> Response:
        """Map framework validation failures to the Java API's stable 400 error envelope."""

        return JSONResponse(
            status_code=400,
            content={"code": "INVALID_REQUEST", "message": "Request validation failed"},
        )

    @app.exception_handler(Exception)
    async def internal_error_handler(_request: Request, exception: Exception) -> Response:
        """Keep unexpected errors behind a stable non-sensitive 500 envelope."""

        import logging

        logging.getLogger(__name__).exception("Unhandled API error", exc_info=exception)
        return JSONResponse(
            status_code=500,
            content={"code": "INTERNAL_ERROR", "message": "The request could not be completed"},
        )
